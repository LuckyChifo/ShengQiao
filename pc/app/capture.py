"""声桥 · 音频采集后端

所有后端统一交付 48 kHz、双声道、float32 的 numpy 数组 (frames, 2)。

- SoundcardSource : Windows WASAPI 环回采集（主力，基于 soundcard 库）
- PyAudioSource   : Windows WASAPI 环回采集（备用，基于 PyAudioWPatch）
- ToneSource      : 测试音 / 正弦波（用于没有声卡的环境和联调）
"""
import os
import sys
import time
import queue
import threading
import warnings

import numpy as np

RATE = 48000          # 统一输出采样率
CHANNELS = 2
BLOCK = 480           # 10 ms 一块
MAILBOX_BLOCKS = 4
MAILBOX_MAX_AGE = 0.060
IS_WINDOWS = sys.platform == 'win32'


import collections

LOG_LINES = collections.deque(maxlen=300)     # 给电脑端控制页看的最近日志
_log_file = None
_log_lock = threading.Lock()


def set_log_file(path):
    """打包成 exe（没有控制台窗口）时，日志写到文件里。"""
    global _log_file
    try:
        if os.path.exists(path) and os.path.getsize(path) > 1024 * 1024:
            os.replace(path, path + '.old')
        _log_file = open(path, 'a', encoding='utf-8', buffering=1)
    except Exception:
        _log_file = None


def log(msg):
    line = time.strftime('[%H:%M:%S] ') + msg
    with _log_lock:
        LOG_LINES.append(line)
        if _log_file is not None:
            try:
                _log_file.write(time.strftime('%Y-%m-%d ') + line + '\n')
            except Exception:
                pass
    if sys.stdout is not None:
        try:
            print(line, flush=True)
        except Exception:
            pass


# ----------------------------------------------------------------- helpers

def to_stereo(x):
    """把任意声道数的 (n, ch) 数组混成双声道。"""
    ch = x.shape[1]
    if ch == 2:
        return x
    if ch == 1:
        return np.repeat(x, 2, axis=1)
    left = x[:, 0].astype(np.float32, copy=True)
    right = x[:, 1].astype(np.float32, copy=True)
    k = 0.7071
    if ch == 4:                      # 四声道：FL FR BL BR
        left += k * x[:, 2]
        right += k * x[:, 3]
    else:
        if ch >= 3:                  # 中置
            left += k * x[:, 2]
            right += k * x[:, 2]
        if ch >= 6:                  # 5.1：FL FR FC LFE BL BR
            left += k * x[:, 4]
            right += k * x[:, 5]
        if ch >= 8:                  # 7.1：... SL SR
            left += k * x[:, 6]
            right += k * x[:, 7]
    out = np.stack([left, right], axis=1)
    np.clip(out, -1.0, 1.0, out=out)
    return out


class LinearResampler:
    """简单的有状态线性插值重采样（仅备用后端在设备不是 48 kHz 时使用）。"""

    def __init__(self, src_rate, dst_rate):
        self.step = src_rate / float(dst_rate)
        self.pos = 0.0
        self.prev = np.zeros((1, CHANNELS), np.float32)

    def process(self, x):
        buf = np.concatenate([self.prev, x])
        last = len(buf) - 1
        if self.pos >= last:
            self.pos -= last
            self.prev = buf[-1:]
            return np.zeros((0, CHANNELS), np.float32)
        p = np.arange(self.pos, last, self.step)
        i = p.astype(np.int64)
        f = (p - i)[:, None].astype(np.float32)
        out = buf[i] * (1.0 - f) + buf[i + 1] * f
        self.pos = p[-1] + self.step - last
        self.prev = buf[-1:]
        return out.astype(np.float32)


# ----------------------------------------------------------------- sources

def play_wasapi_stereo(player, samples):
    """Write only the supplied frames to a SoundCard stereo player on Windows."""
    from soundcard.mediafoundation import _ffi

    # SoundCard's play() can submit all available frames even when the input is shorter.
    samples = np.ascontiguousarray(samples, dtype=np.float32)
    if samples.ndim != 2 or samples.shape[1] != 2:
        raise ValueError('WASAPI output requires stereo samples')
    offset = 0
    while offset < len(samples):
        frames = min(player._render_available_frames(), len(samples) - offset)
        if frames <= 0:
            time.sleep(0.001)
            continue
        chunk = samples[offset:offset + frames]
        source = _ffi.from_buffer(chunk)
        buffer = player._render_buffer(frames)
        _ffi.memmove(buffer[0], source, chunk.nbytes)
        player._render_release(frames)
        offset += frames


class SourceBase:
    kind = 'base'
    label = ''

    def list_devices(self):
        return []

    def run(self, device_pref, emit, stop, opened):
        """阻塞运行采集，直到 stop 被置位或设备变化。

        emit(x)      : 交付 (n, 2) float32 数据
        opened(name) : 设备打开成功后回调
        """
        raise NotImplementedError


class SoundcardSource(SourceBase):
    """WASAPI 环回采集。

    soundcard 在 import 时会在当前线程执行 CoInitializeEx(MTA)；
    如果该线程之前已经初始化过 COM 会直接报错，所以必须在主线程、
    其他任何 COM 调用之前创建本对象。其他线程随后以“隐式 MTA”使用 COM。
    """

    kind = 'soundcard'
    label = 'WASAPI 环回'

    def __init__(self, keepalive=True):
        import soundcard as sc
        self.sc = sc
        warnings.filterwarnings('ignore', category=sc.SoundcardRuntimeWarning)
        # 环回采集在“没有任何程序发声”时拿不到数据；放一路静音流让音频引擎保持运转，
        # 这样采集出来的是连续、按声卡时钟走的数据流，手机端播放更平稳。
        self.keepalive = keepalive and IS_WINDOWS

    def _speaker(self, device_pref):
        sc = self.sc
        if device_pref and device_pref != 'default':
            try:
                return sc.get_speaker(device_pref), False
            except Exception:
                log('找不到指定的设备“%s”，改用系统默认输出设备' % device_pref)
        return sc.default_speaker(), True

    def list_devices(self):
        sc = self.sc
        try:
            default_id = sc.default_speaker().id
        except Exception:
            default_id = None
        out = []
        for spk in sc.all_speakers():
            try:
                name = spk.name
            except Exception:
                name = spk.id
            out.append({'id': spk.id, 'name': name, 'default': spk.id == default_id})
        return out

    def _keepalive_loop(self, spk, stop):
        try:
            with spk.player(samplerate=RATE, channels=CHANNELS, blocksize=RATE // 10) as p:
                n = RATE // 50                                          # 20 ms
                silence = np.zeros((n, CHANNELS), np.float32)
                while not stop.is_set():
                    try:
                        room = p.buffersize - p.currentpadding
                    except Exception:
                        room = n
                    if room >= n:
                        play_wasapi_stereo(p, silence)
                    else:
                        stop.wait(0.01)
        except Exception as e:  # 不影响主采集
            log('静音保活流不可用（不影响使用）：%s' % e)

    def run(self, device_pref, emit, stop, opened):
        sc = self.sc
        spk, follow_default = self._speaker(device_pref)
        dev_id = spk.id
        dev_name = spk.name
        mic = sc.get_microphone(dev_id, include_loopback=True)

        ka_stop = threading.Event()
        if self.keepalive:
            threading.Thread(target=self._keepalive_loop, args=(spk, ka_stop),
                             name='keepalive', daemon=True).start()
        try:
            # blocksize 只决定 WASAPI 缓冲区大小（100 ms 余量，防止 GIL 抢占导致丢数据），
            # 数据仍按设备周期（通常 10 ms）一包一包到达，不增加延迟。
            with mic.recorder(samplerate=RATE, channels=CHANNELS, blocksize=RATE // 10) as rec:
                opened(dev_name)
                last_check = time.monotonic()
                while not stop.is_set():
                    data = rec.record(numframes=None)
                    if data.size:
                        emit(np.ascontiguousarray(data, dtype=np.float32))
                    now = time.monotonic()
                    if follow_default and now - last_check > 2.0:
                        last_check = now
                        try:
                            if sc.default_speaker().id != dev_id:
                                log('检测到系统默认输出设备已切换，正在跟随……')
                                return 'device-changed'
                        except Exception:
                            pass
        finally:
            ka_stop.set()
        return 'stopped'


class PyAudioSource(SourceBase):
    """备用：PyAudioWPatch 的 WASAPI 环回。没有声音时不产生数据，由服务器补静音。"""

    kind = 'pyaudio'
    label = 'WASAPI 环回（备用）'

    def __init__(self):
        import pyaudiowpatch as pyaudio
        self.pa = pyaudio
        p = pyaudio.PyAudio()          # 立即验证 WASAPI 可用
        try:
            p.get_host_api_info_by_type(pyaudio.paWASAPI)
        finally:
            p.terminate()

    def list_devices(self):
        p = self.pa.PyAudio()
        try:
            try:
                default_index = p.get_default_wasapi_loopback()['index']
            except Exception:
                default_index = None
            out = []
            for d in p.get_loopback_device_info_generator():
                name = d['name'].replace(' [Loopback]', '')
                out.append({'id': name, 'name': name, 'default': d['index'] == default_index})
            return out
        finally:
            p.terminate()

    def run(self, device_pref, emit, stop, opened):
        pyaudio = self.pa
        p = pyaudio.PyAudio()
        stream = None
        try:
            dev = None
            if device_pref and device_pref != 'default':
                for d in p.get_loopback_device_info_generator():
                    if device_pref in d['name']:
                        dev = d
                        break
            if dev is None:
                dev = p.get_default_wasapi_loopback()
            ch = max(1, int(dev['maxInputChannels']))
            rate = int(dev['defaultSampleRate'])
            q = queue.Queue(maxsize=MAILBOX_BLOCKS)

            def callback(in_data, frame_count, time_info, status):
                item = (time.monotonic(), in_data)
                try:
                    q.put_nowait(item)
                except queue.Full:
                    try:
                        q.get_nowait()
                    except queue.Empty:
                        pass
                    q.put_nowait(item)
                return (None, pyaudio.paContinue)

            stream = p.open(format=pyaudio.paFloat32, channels=ch, rate=rate, input=True,
                            input_device_index=dev['index'],
                            frames_per_buffer=max(64, rate // 100),
                            stream_callback=callback)
            stream.start_stream()
            opened(dev['name'].replace(' [Loopback]', ''))
            resampler = LinearResampler(rate, RATE) if rate != RATE else None
            while not stop.is_set():
                try:
                    captured_at, buf = q.get(timeout=0.1)
                except queue.Empty:
                    continue
                if time.monotonic() - captured_at > MAILBOX_MAX_AGE:
                    continue
                x = np.frombuffer(buf, dtype=np.float32).reshape(-1, ch)
                x = to_stereo(x)
                if resampler is not None:
                    x = resampler.process(x)
                if len(x):
                    emit(np.ascontiguousarray(x, dtype=np.float32), captured_at)
        finally:
            if stream is not None:
                try:
                    stream.stop_stream()
                    stream.close()
                except Exception:
                    pass
            p.terminate()
        return 'stopped'


class ToneSource(SourceBase):
    """按真实时间节拍生成测试音。drift_ppm 用于模拟两台设备时钟不一致。"""

    def __init__(self, kind='tone', drift_ppm=0.0):
        self.kind = kind
        self.label = '正弦测试音 440 Hz' if kind == 'sine' else '测试音（叮咚旋律）'
        self.speed = 1.0 + drift_ppm * 1e-6
        self.n = 0

    def list_devices(self):
        return [{'id': self.kind, 'name': self.label, 'default': True}]

    def _gen(self, n):
        t = (self.n + np.arange(n)) / RATE
        self.n += n
        if self.kind == 'sine':
            s = 0.25 * np.sin(2 * np.pi * 440.0 * t)
            return np.stack([s, s], axis=1).astype(np.float32)
        tp = np.mod(t, 2.4)
        left = np.zeros(n)
        right = np.zeros(n)
        notes = (523.25, 659.25, 783.99, 1046.50)
        for k, f in enumerate(notes):
            dt = tp - k * 0.16
            env = np.where(dt >= 0, np.exp(-np.clip(dt, 0, None) / 0.4) * np.clip(dt / 0.005, 0, 1), 0.0)
            v = 0.16 * env * (np.sin(2 * np.pi * f * t) + 0.25 * np.sin(4 * np.pi * f * t))
            pan = k / (len(notes) - 1)
            left += v * (1.1 - 0.5 * pan)
            right += v * (0.6 + 0.5 * pan)
        return np.stack([left, right], axis=1).astype(np.float32)

    def run(self, device_pref, emit, stop, opened):
        opened(self.label)
        t0 = time.monotonic()
        produced = 0
        while not stop.is_set():
            due = int((time.monotonic() - t0) * RATE * self.speed)
            if due - produced > RATE:               # 被挂起过，重新对齐
                produced = due - BLOCK
            while produced + BLOCK <= due:
                emit(self._gen(BLOCK))
                produced += BLOCK
            time.sleep(0.004)
        return 'stopped'


def make_source(name='auto', keepalive=True, drift_ppm=0.0):
    errors = []
    if name in ('tone', 'sine'):
        return ToneSource(name, drift_ppm)
    if name in ('auto', 'soundcard') and (IS_WINDOWS or name == 'soundcard'):
        try:
            return SoundcardSource(keepalive=keepalive)
        except Exception as e:
            errors.append('soundcard: %r' % (e,))
    if name in ('auto', 'pyaudio') and (IS_WINDOWS or name == 'pyaudio'):
        try:
            return PyAudioSource()
        except Exception as e:
            errors.append('pyaudiowpatch: %r' % (e,))
    if name == 'auto' and not IS_WINDOWS:
        log('当前不是 Windows，系统声音采集不可用，改为播放测试音。')
        return ToneSource('tone', drift_ppm)
    raise RuntimeError('无法初始化音频采集：' + '；'.join(errors or ['未知的采集方式 %s' % name]))


# ----------------------------------------------------------------- runner

class CaptureMailbox:
    """采集线程到事件循环的有界交接；drain 和丢弃通知都在事件循环执行。"""

    def __init__(self, loop, on_block, on_discontinuity=None,
                 max_blocks=MAILBOX_BLOCKS, max_age=MAILBOX_MAX_AGE, clock=time.monotonic):
        if max_blocks < 1 or max_age <= 0:
            raise ValueError('invalid capture mailbox limits')
        self.loop = loop
        self.on_block = on_block
        self.on_discontinuity = on_discontinuity
        self.max_blocks = max_blocks
        self.max_age = max_age
        self.clock = clock
        self.dropped = 0
        self.discontinuities = 0
        self.last_delivery_age_ms = None
        self._items = collections.deque()
        self._pending_drops = 0
        self._source_changed = False
        self._scheduled = False
        self._closed = False
        self._lock = threading.Lock()

    def offer(self, block, produced_at=None):
        now = self.clock()
        produced_at = now if produced_at is None else produced_at
        with self._lock:
            if self._closed:
                return
            self._items.append((produced_at, block))
            dropped = 0
            while self._items and now - self._items[0][0] > self.max_age:
                self._items.popleft()
                dropped += 1
            while len(self._items) > self.max_blocks:
                self._items.popleft()
                dropped += 1
            self.dropped += dropped
            self._pending_drops += dropped
            if self._scheduled:
                return
            self._scheduled = True
            try:
                self.loop.call_soon_threadsafe(self._drain)
            except RuntimeError:
                self._scheduled = False
                self._closed = True
                self._items.clear()
                self._pending_drops = 0

    def mark_discontinuity(self):
        with self._lock:
            if self._closed:
                return
            self._pending_drops += len(self._items)
            self.dropped += len(self._items)
            self._items.clear()
            self._source_changed = True

    def _drain(self):
        with self._lock:
            if self._closed:
                self._scheduled = False
                return
            items = list(self._items)
            self._items.clear()
            dropped = self._pending_drops
            self._pending_drops = 0
            source_changed = self._source_changed
            self._source_changed = False
        try:
            now = self.clock()
            fresh = [item for item in items if now - item[0] <= self.max_age]
            new_drops = len(items) - len(fresh)
            # Sources can deliver two adjacent 10 ms blocks at once. Keep the
            # short fresh batch, but recover from a stalled loop at its live edge.
            keep = 1 if len(items) > 2 or dropped else 2
            new_drops += max(0, len(fresh) - keep)
            fresh = fresh[-keep:]
            dropped += new_drops
            with self._lock:
                self.dropped += new_drops
                if dropped or source_changed:
                    self.discontinuities += 1
                if fresh:
                    self.last_delivery_age_ms = max(0.0, (now - fresh[-1][0]) * 1000)
            if (dropped or source_changed) and self.on_discontinuity is not None:
                self.on_discontinuity(dropped)
            for produced_at, block in fresh:
                self.on_block(block, produced_at)
        finally:
            with self._lock:
                if self._closed:
                    self._items.clear()
                    self._pending_drops = 0
                    self._scheduled = False
                elif self._items:
                    self.loop.call_soon(self._drain)
                else:
                    self._scheduled = False

    def stats(self):
        with self._lock:
            count = len(self._items)
            oldest = self._items[0][0] if self._items else None
            return {
                'queueMs': count * BLOCK * 1000 / RATE,
                'oldestAgeMs': 0 if oldest is None else max(0.0, (self.clock() - oldest) * 1000),
                'dropped': self.dropped,
                'discontinuities': self.discontinuities,
                'lastDeliveryAgeMs': self.last_delivery_age_ms,
            }

    def close(self):
        with self._lock:
            self._closed = True
            self._items.clear()
            self._pending_drops = 0


class CaptureRunner:
    """在后台线程里运行采集源，按需启停，出错自动重试，切成 10 ms 的块交出去。"""

    def __init__(self, source, on_block, device_pref=None, fallback=None, on_discontinuity=None):
        self.source = source
        self.fallback = fallback          # 主采集方式连续出错时，换用的备用采集源（工厂函数）
        self.on_block = on_block
        self.on_discontinuity = on_discontinuity
        self.device_pref = device_pref
        self.state = 'idle'               # idle / opening / running / error
        self.error = ''
        self.device_name = ''
        self._want = threading.Event()
        self._stop_run = threading.Event()
        self._acc = np.zeros((0, CHANNELS), np.float32)
        self._acc_stamp = None
        threading.Thread(target=self._main, name='capture', daemon=True).start()

    @property
    def active(self):
        return self._want.is_set()

    def activate(self, on):
        if on:
            self._want.set()
        elif self._want.is_set():
            self._want.clear()
            self._stop_run.set()

    def set_device(self, pref):
        self.device_pref = pref
        self._stop_run.set()

    def _opened(self, name):
        self.device_name = name
        self.state = 'running'
        self.error = ''
        log('开始采集：%s' % name)

    def _emit(self, x, captured_at=None):
        if captured_at is None:
            captured_at = time.monotonic()
        stamp = captured_at if self._acc_stamp is None else self._acc_stamp
        acc = np.concatenate([self._acc, x]) if len(self._acc) else x
        nb = len(acc) // BLOCK
        for i in range(nb):
            self.on_block(acc[i * BLOCK:(i + 1) * BLOCK].copy(), stamp if i == 0 else captured_at)
        self._acc = acc[nb * BLOCK:].copy()
        self._acc_stamp = (captured_at if nb else stamp) if len(self._acc) else None

    def _main(self):
        delay = 1.0
        failures = 0
        while True:
            self._want.wait()
            self._stop_run.clear()
            self._acc = np.zeros((0, CHANNELS), np.float32)
            self._acc_stamp = None
            self.state = 'opening'
            ran = [False]

            def opened(name):
                ran[0] = True
                if self.on_discontinuity is not None:
                    self.on_discontinuity()
                self._opened(name)
            try:
                self.source.run(self.device_pref, self._emit, self._stop_run, opened)
                self.state = 'idle'
                delay = 1.0
                failures = 0
            except Exception as e:
                self.state = 'error'
                self.error = '%s: %s' % (type(e).__name__, e)
                failures = 0 if ran[0] else failures + 1
                if failures >= 2 and self.fallback is not None:
                    factory, self.fallback = self.fallback, None
                    try:
                        self.source = factory()
                        log('采集出错：%s；改用备用采集方式：%s' % (self.error, self.source.label))
                        failures = 0
                        continue
                    except Exception as e2:
                        log('备用采集方式也不可用：%s' % e2)
                log('采集出错（%.0f 秒后重试）：%s' % (delay, self.error))
                self._stop_run.wait(delay)
                delay = min(delay * 2, 8.0)
