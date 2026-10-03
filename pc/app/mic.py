"""声桥 · 手机麦克风 → 电脑（写进 VB-CABLE 的 CABLE Input，其他软件选 CABLE Output 当麦克风）

MicJitter : 自适应抖动缓冲 + 漂移补偿重采样（与 iPhone 端播放核心同一套算法的 numpy 版，单声道）
MicSink   : 后台线程，把 MicJitter 的输出按声卡节拍写进输出设备
"""
import threading
import time

import numpy as np

import denoise
from capture import log, IS_WINDOWS

OUT_RATE = 48000
BLOCK = 480                      # 每次写 10 ms
KP = 0.1                         # 变速比例系数
MAXADJ = 0.005                   # 最大变速 ±0.5%
EMA_TAU = 1.0
FADE = 96


def _catmull(y0, y1, y2, y3, f):
    return y1 + 0.5 * f * (y2 - y0 + f * (2 * y0 - 5 * y1 + 4 * y2 - y3 + f * (3 * (y1 - y2) + y3 - y0)))


class MicJitter:
    """push() 在网络线程调用，pull() 在声卡线程调用，内部加锁（临界区很短）。"""

    def __init__(self, src_rate=48000, dst_rate=OUT_RATE, target_ms=40.0):
        self.lock = threading.Lock()
        self.dst_rate = dst_rate
        self.base_target = target_ms / 1000.0
        self.extra = 0.0
        self.max_extra = 0.2
        self.set_src_rate(src_rate)
        self.underruns = 0
        self.skips = 0
        self.frames_in = 0
        self.clock = 0.0
        self.peak = 0.0
        self._reset()

    def set_src_rate(self, rate):
        self.src_rate = float(rate)
        self.ratio0 = self.src_rate / self.dst_rate

    def _reset(self):
        self.buf = np.zeros(2, np.float32)   # 前面留 1 个历史样本给三次插值
        self.pos = 1.0                       # 读位置（buf 下标，含小数）
        self.state = 'buffering'
        self.env = 0.0
        self.ema = 0.0
        self.last = 0.0
        self.last_underrun = -1e9
        self.last_relax = self.clock
        self.play_start = self.clock
        self.win_min = 1e9

    def reset(self):
        with self.lock:
            self._reset()

    def target(self):
        return self.base_target + self.extra

    def level(self):
        return max(0.0, len(self.buf) - 2 - self.pos) / self.src_rate

    def push(self, pcm16):
        x = pcm16.astype(np.float32) * (1.0 / 32768.0)
        if len(x):
            p = float(np.max(np.abs(x)))
            if p > self.peak:
                self.peak = p
        with self.lock:
            self.buf = np.concatenate([self.buf, x])
            self.frames_in += len(x)
            # 太久没被取走（比如声卡线程卡住）：只留最新的一小段
            if self.level() > 1.5:
                keep = int((self.target() + 0.05) * self.src_rate)
                self.buf = self.buf[-(keep + 3):].copy()
                self.pos = 1.0
                self.skips += 1

    def pull(self, n):
        with self.lock:
            return self._pull(n)

    def _tail(self, n, start_val):
        out = start_val * (0.96 ** np.arange(1, n + 1, dtype=np.float64))
        return out.astype(np.float32)

    def _interp(self, pos, count, step):
        b = self.buf
        pp = pos + np.arange(count, dtype=np.float64) * step
        i = pp.astype(np.int64)
        f = (pp - i).astype(np.float32)
        return _catmull(b[i - 1], b[i], b[i + 1], b[i + 2], f), pos + count * step

    def _pull(self, n):
        dt = n / self.dst_rate
        self.clock += dt
        target = self.target()
        level = self.level()
        self.ema += (level - self.ema) * (1 - np.exp(-dt / EMA_TAU))
        if self.clock - max(self.last_underrun, self.last_relax) > 15:
            if self.extra > 0 and self.win_min > 0.03:
                self.extra = max(0.0, self.extra - 0.01)
            self.last_relax = self.clock
            self.win_min = 1e9

        if self.state == 'buffering':
            if level >= target and len(self.buf) - self.pos >= 4:
                if level - target > 0.02:                 # 积压太多：直接从目标水位开始（反正是淡入）
                    self.pos = float(len(self.buf) - 2 - max(4, int(round(target * self.src_rate))))
                    level = self.level()
                self.state = 'playing'
                self.env = 0.0
                self.ema = level
                self.play_start = self.clock
            else:
                out = self._tail(n, self.last)
                self.last = float(out[-1]) if abs(out[-1]) > 1e-6 else 0.0
                return out

        adj = float(np.clip((self.ema - target) * KP, -MAXADJ, MAXADJ))
        step = self.ratio0 * (1 + adj)
        out = np.zeros(n, np.float32)
        start = 0

        # 网络卡顿后一次性涌进来：交叉淡化跳到目标水位
        if level - target > max(0.15, target) + 0.1:
            new_pos = float(len(self.buf) - 2 - int(round(target * self.src_rate)))
            xf = min(FADE, n)
            if new_pos > self.pos + 2 * FADE and len(self.buf) - 3 > new_pos + xf * step + 1:
                old, _ = self._interp(self.pos, xf, step)
                new, self.pos = self._interp(new_pos, xf, step)
                a = (np.arange(1, xf + 1, dtype=np.float32) / xf)
                out[:xf] = old * self.env * (1 - a) + new * a
                self.env = 1.0
                self.ema = self.level()
                self.skips += 1
                start = xf

        m = n - start
        avail_max_idx = len(self.buf) - 3                      # 需要 i+2 < len(buf)
        p = self.pos + np.arange(m + FADE, dtype=np.float64) * step
        ok_all = int(np.searchsorted(p, avail_max_idx, side='right'))
        # 正常情况下留 FADE 个样本作“储备”：一旦快断流，总有足够的样本做完整的淡出
        starving = ok_all < m + FADE
        ok = min(m, ok_all) if starving else m
        if ok > 0:
            y, _ = self._interp(self.pos, ok, step)
            env = np.minimum(1.0, self.env + (np.arange(1, ok + 1) / FADE)).astype(np.float32)
            self.env = float(env[-1])
            if starving:                                       # 用储备做淡出
                fl = min(FADE, ok)
                env[ok - fl:] *= np.linspace(1, 0, fl, endpoint=False, dtype=np.float32)
            out[start:start + ok] = y * env
        self.pos = self.pos + ok * step
        if starving:
            z = start + ok
            if z < n:
                first = float(out[z - 1]) if z > 0 else self.last
                out[z:] = self._tail(n - z, first)
            self.state = 'buffering'
            self.underruns += 1
            if self.clock - self.play_start > 1.5:
                self.extra = min(self.max_extra, self.extra + 0.02)
            self.last_underrun = self.clock
            self.win_min = 1e9
        else:
            lv = self.level()
            if lv < self.win_min:
                self.win_min = lv
        self.last = float(out[-1])
        drop = int(self.pos) - 1                               # 丢掉已读过的数据（保留 1 个历史样本）
        if drop > 4096:
            self.buf = self.buf[drop:].copy()
            self.pos -= drop
        np.clip(out, -1.0, 1.0, out=out)
        return out

    def stats(self):
        return {'state': self.state, 'levelMs': round(self.level() * 1000, 1), 'emaMs': round(self.ema * 1000, 1),
                'targetMs': round(self.target() * 1000), 'underruns': self.underruns, 'skips': self.skips}


# ------------------------------------------------------------------ output devices

def find_cable(devices):
    """在输出设备列表里找 VB-CABLE 的 CABLE Input。"""
    for d in devices:
        n = d['name'].lower()
        if 'cable input' in n:
            return d
    for d in devices:
        n = d['name'].lower()
        if 'vb-audio' in n or ('cable' in n and 'virtual' in n):
            return d
    return None


class SoundcardOutput:
    """WASAPI 输出（soundcard）。"""

    def __init__(self):
        import soundcard as sc
        self.sc = sc

    def list(self):
        out = []
        for spk in self.sc.all_speakers():
            try:
                out.append({'id': spk.id, 'name': spk.name})
            except Exception:
                pass
        return out

    def default_id(self):
        try:
            return self.sc.default_speaker().id
        except Exception:
            return None

    def open(self, dev_id):
        spk = self.sc.get_speaker(dev_id)
        return spk.name, spk.player(samplerate=OUT_RATE, channels=2, blocksize=OUT_RATE // 10)


class FakeOutput:
    """测试用：按（可带时钟偏差的）真实时间消费数据，记录写出的声音。"""

    class _Player:
        def __init__(self, owner):
            self.o = owner
            self.t0 = None
            self.written = 0

        def __enter__(self):
            self.t0 = time.monotonic()
            return self

        def __exit__(self, *a):
            return False

        @property
        def buffersize(self):
            return OUT_RATE // 10

        @property
        def currentpadding(self):
            consumed = int((time.monotonic() - self.t0) * OUT_RATE * self.o.speed)
            pad = self.written - consumed
            if pad < 0:                       # 声卡“饿”了：补成 0，相当于输出静音
                self.written = consumed
                self.o.starved += 1
                pad = 0
            return pad

        def play(self, data):
            if self.o.keep_frames:
                self.o.record.append(np.asarray(data)[:, 0].copy())
                self.o.kept += len(data)
                while self.o.kept > self.o.keep_frames and len(self.o.record) > 1:
                    self.o.kept -= len(self.o.record.pop(0))
            self.written += len(data)

    def __init__(self, name='CABLE Input (VB-Audio Virtual Cable)', drift_ppm=0.0, keep_seconds=0):
        self.name = name
        self.speed = 1 + drift_ppm * 1e-6
        self.record = []
        self.kept = 0
        self.keep_frames = int(keep_seconds * OUT_RATE)
        self.starved = 0

    def recording(self):
        return np.concatenate(self.record) if self.record else np.zeros(0, np.float32)

    def list(self):
        return [{'id': 'fake-cable', 'name': self.name}, {'id': 'fake-spk', 'name': 'Speakers (Realtek(R) Audio)'}]

    def default_id(self):
        return 'fake-spk'

    def open(self, dev_id):
        return self.name, FakeOutput._Player(self)


class MicSink:
    """管理“手机麦克风 → 输出设备”这条链路。

    feed(pcm16, rate)  网络线程调用
    start()/stop()     有手机开麦/关麦时调用
    """

    OUT_PAD = 0.02            # 声卡缓冲里保持约 20 ms

    def __init__(self, output=None, device_pref='auto', denoise_mode='rnnoise'):
        self.output = output
        self.device_pref = device_pref or 'auto'
        self.jitter = MicJitter()
        self.denoiser = denoise.Denoiser(denoise_mode)
        self.denoise_mode = denoise_mode
        self.state = 'off'        # off / starting / running / no-cable / error
        self.error = ''
        self.device_name = ''
        self.owner = None         # 当前在用麦克风的连接
        self.last_feed = 0.0
        self._want = threading.Event()
        self._stop = threading.Event()
        self._thread = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------ device
    def list_devices(self):
        if self.output is None:
            return []
        try:
            return self.output.list()
        except Exception as e:
            log('列出输出设备失败：%s' % e)
            return []

    def pick_device(self):
        devs = self.list_devices()
        if self.device_pref not in ('auto', '', None):
            for d in devs:
                if d['id'] == self.device_pref:
                    return d
        return find_cable(devs)

    def set_device(self, pref):
        self.device_pref = pref or 'auto'
        self._stop.set()              # 线程会用新设备重开

    # ------------------------------------------------------------ control
    def start(self, owner, rate):
        with self._lock:
            self.owner = owner
            self.jitter.set_src_rate(rate)
            self.jitter.reset()
            self.denoiser.reset()
            self.last_feed = time.monotonic()
            self._want.set()
            if self._thread is None:
                self._thread = threading.Thread(target=self._main, name='mic-sink', daemon=True)
                self._thread.start()

    def stop(self, owner=None):
        with self._lock:
            if owner is not None and owner is not self.owner:
                return
            self.owner = None
            self._want.clear()
            self._stop.set()

    def set_denoise(self, mode):
        if mode not in denoise.MODES:
            mode = 'off'
        self.denoise_mode = mode
        self.denoiser.set_mode(mode)

    def feed(self, owner, pcm16):
        if owner is not self.owner:
            return
        self.last_feed = time.monotonic()
        self.jitter.push(pcm16)

    def status(self):
        st = self.jitter.stats()
        peak, self.jitter.peak = self.jitter.peak, 0.0
        dn_delay = {'off': 0, 'light': 10, 'rnnoise': 20, 'strong': 20}.get(self.denoise_mode, 0)
        return {'state': self.state, 'error': self.error, 'device': self.device_name,
                'active': self.owner is not None, 'level': round(peak, 4),
                'latencyMs': round(st['emaMs'] + self.OUT_PAD * 1000 + dn_delay), 'underruns': st['underruns'],
                'devicePref': self.device_pref, 'denoise': self.denoise_mode,
                'denoiseModes': denoise.available_modes(), 'denoiseError': self.denoiser.error}

    # ------------------------------------------------------------ thread
    def _main(self):
        while True:
            self._want.wait()
            self._stop.clear()
            dev = self.pick_device() if self.output is not None else None
            if dev is None:
                self.state = 'no-cable'
                self.device_name = ''
                self.error = '没找到 VB-CABLE（CABLE Input）'
                self._stop.wait(3.0)
                continue
            try:
                self.state = 'starting'
                name, player = self.output.open(dev['id'])
                self.device_name = name
                with player as p:
                    self.state = 'running'
                    self.error = ''
                    log('手机麦克风 → %s' % name)
                    self._pump(p)
                self.state = 'off'
            except Exception as e:
                self.state = 'error'
                self.error = '%s: %s' % (type(e).__name__, e)
                log('麦克风输出出错：%s' % self.error)
                self._stop.wait(2.0)

    def _pump(self, p):
        pad_target = int(self.OUT_PAD * OUT_RATE)
        while not self._stop.is_set():
            try:
                pad = p.currentpadding
            except Exception:
                pad = 0
            if pad < pad_target:
                blk = self.denoiser.process(self.jitter.pull(BLOCK))
                p.play(np.repeat(blk[:, None], 2, axis=1))
            else:
                time.sleep(0.002)
