"""声桥 · 麦克风降噪（电脑端，48 kHz 单声道，每次 480 个样本 = 10 ms）

模式：
  off      关闭
  light    轻度：频谱减法（Speex 风格），对风扇、空调这类稳定噪声有效，几乎不损伤人声
  rnnoise  RNNoise：神经网络降噪（xiph.org），键盘、杂音也能压，推荐
  strong   强力：RNNoise + 人声检测噪声门，不说话时几乎完全静音
"""
import ctypes
import os
import sys
import threading

import numpy as np

from capture import log

FRAME = 480
MODES = ['off', 'light', 'rnnoise', 'strong']
MODE_NAMES = {'off': '关闭', 'light': '轻度（频谱减法）', 'rnnoise': 'RNNoise 神经网络', 'strong': '强力（RNNoise + 噪声门）'}

_lib = None
_lib_error = None
_lib_lock = threading.Lock()


def _lib_dirs():
    here = os.path.dirname(os.path.abspath(__file__))
    dirs = [os.path.join(here, 'lib')]
    base = getattr(sys, '_MEIPASS', None)
    if base:
        dirs.insert(0, os.path.join(base, 'lib'))
    return dirs


def load_rnnoise():
    """加载 RNNoise（Windows: lib/rnnoise.dll；Linux/macOS: lib/librnnoise.so，测试用）。"""
    global _lib, _lib_error
    with _lib_lock:
        if _lib is not None or _lib_error is not None:
            return _lib
        names = ['rnnoise.dll'] if sys.platform == 'win32' else ['librnnoise.so', 'librnnoise.dylib']
        for d in _lib_dirs():
            for n in names:
                p = os.path.join(d, n)
                if os.path.exists(p):
                    try:
                        lib = ctypes.CDLL(p)
                        lib.rnnoise_create.restype = ctypes.c_void_p
                        lib.rnnoise_create.argtypes = [ctypes.c_void_p]
                        lib.rnnoise_destroy.argtypes = [ctypes.c_void_p]
                        lib.rnnoise_process_frame.restype = ctypes.c_float
                        lib.rnnoise_process_frame.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
                        _lib = lib
                        return _lib
                    except OSError as e:
                        _lib_error = '%s: %s' % (p, e)
        if _lib_error is None:
            _lib_error = '找不到 RNNoise 库'
        return None


def available_modes():
    return [m for m in MODES if m not in ('rnnoise', 'strong') or load_rnnoise() is not None]


class SpectralSubtraction:
    """960 点 sqrt-Hann 窗、50% 重叠的频谱减法。延迟 10 ms。

    噪声谱用“最小值跟踪”估计：功率比当前估计小就立刻跟下来，大就每帧慢慢涨（约 3 秒翻倍），
    这样说话时噪声估计不会被人声带上去。增益做时间平滑，减少“音乐噪声”。
    """

    def __init__(self, over=3.25, floor=0.06):
        n = 2 * FRAME
        self.win = np.sqrt(0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)).astype(np.float64)
        self.prev_in = np.zeros(FRAME)
        self.prev_out = np.zeros(FRAME)
        self.noise = None
        self.power = None
        self.gain = None
        self.over = over          # 过减系数（含最小值跟踪的偏低补偿）
        self.floor = floor        # 最多压到 -24 dB，避免“水下”音质
        self.frames = 0

    def process(self, x):
        frame = np.concatenate([self.prev_in, x.astype(np.float64)])
        self.prev_in = x.astype(np.float64)
        spec = np.fft.rfft(frame * self.win)
        p = spec.real ** 2 + spec.imag ** 2
        if np.mean(frame * frame) < 1e-12:
            # 数字静音（刚开麦、缓冲中）：不更新噪声估计，否则噪声底会被拉到 0、之后很久都恢复不了
            y = np.fft.irfft(spec * (self.gain if self.gain is not None else 1.0), 2 * FRAME) * self.win
            out = self.prev_out + y[:FRAME]
            self.prev_out = y[FRAME:]
            return out.astype(np.float32)
        if self.power is None:
            self.power = p.copy()
            self.noise = p.copy() + 1e-12
            self.gain = np.ones_like(p)
        self.power = 0.9 * self.power + 0.1 * p
        self.frames += 1
        rise = 1.003 if self.frames > 50 else 1.05          # 刚开始时噪声估计爬得快一些
        self.noise = np.where(self.power < self.noise, self.power, self.noise * rise)
        self.noise = np.maximum(self.noise, 1e-3 * self.power) + 1e-12  # 不低于当前功率的 -30 dB，静音后能很快恢复
        g = np.maximum(1.0 - self.over * self.noise / (0.5 * p + 0.5 * self.power + 1e-12), self.floor)
        g = np.sqrt(g)                                        # 幅度增益
        self.gain = 0.5 * self.gain + 0.5 * g
        y = np.fft.irfft(spec * self.gain, 2 * FRAME) * self.win
        out = self.prev_out + y[:FRAME]
        self.prev_out = y[FRAME:]
        return out.astype(np.float32)


class RNNoise:
    WARMUP = 20                                               # 刚启动的 200 ms 模型还没进入状态，先静音再淡入

    def __init__(self, lib):
        self.lib = lib
        self.st = lib.rnnoise_create(None)
        self.inp = np.zeros(FRAME, np.float32)
        self.out = np.zeros(FRAME, np.float32)
        self.vad = 0.0
        self.frames = 0

    def process(self, x):
        np.multiply(x, 32768.0, out=self.inp)                 # RNNoise 的输入是 16 位整数的数值范围
        self.vad = float(self.lib.rnnoise_process_frame(self.st, self.out.ctypes.data, self.inp.ctypes.data))
        y = self.out * (1.0 / 32768.0)
        self.frames += 1
        if self.frames <= self.WARMUP:
            y = y * 0.0
        elif self.frames <= self.WARMUP + 5:                  # 50 ms 淡入
            k = self.frames - self.WARMUP
            y = y * np.linspace((k - 1) / 5, k / 5, FRAME, dtype=np.float32)
        return y

    def close(self):
        if self.st:
            self.lib.rnnoise_destroy(self.st)
            self.st = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass


class VoiceGate:
    """根据 RNNoise 的人声概率开关。概率先做平滑，连续两三帧像人声才打开（单帧误判不会触发）；
    说完后保持 200 ms，再慢慢关到 -30 dB。"""

    def __init__(self):
        self.g = 0.03                                         # 开始时是关着的
        self.v = 0.0
        self.hold = 0

    def process(self, x, vad):
        self.v = 0.6 * self.v + 0.4 * vad
        if self.v > 0.5:
            self.hold = 20                                    # 20 帧 = 200 ms
        elif self.hold > 0:
            self.hold -= 1
        target = 1.0 if self.hold > 0 else 0.03
        k = 0.5 if target > self.g else 0.12                  # 开得快（约 10 ms），关得慢（约 80 ms）
        ramp = self.g + (target - self.g) * (1 - (1 - k) ** np.linspace(1 / FRAME, 1, FRAME))
        self.g = float(ramp[-1])
        return (x * ramp).astype(np.float32)


class Denoiser:
    """切换模式是线程安全的：set_mode 只记下来，下一次 process（在声卡线程里）才真正切换。"""

    def __init__(self, mode='rnnoise'):
        self.mode = 'off'
        self.pending = mode
        self.impl = None
        self.gate = None
        self.vad = 0.0
        self.error = ''

    def set_mode(self, mode):
        if mode not in MODES:
            mode = 'off'
        self.pending = mode

    def reset(self):
        self.pending = self.pending or self.mode
        self.mode = None                                      # 下次 process 时重建（清空内部状态）

    def _switch(self, mode):
        if isinstance(self.impl, RNNoise):
            self.impl.close()
        self.impl, self.gate, self.error = None, None, ''
        if mode == 'light':
            self.impl = SpectralSubtraction()
        elif mode in ('rnnoise', 'strong'):
            lib = load_rnnoise()
            if lib is None:
                self.error = 'RNNoise 不可用（%s），已改用轻度降噪' % _lib_error
                log(self.error)
                self.impl = SpectralSubtraction()
            else:
                self.impl = RNNoise(lib)
                if mode == 'strong':
                    self.gate = VoiceGate()
        self.mode = mode

    def process(self, x):
        if self.pending is not None and (self.pending != self.mode or self.mode is None):
            self._switch(self.pending)
        self.pending = None if self.pending == self.mode else self.pending
        if self.impl is None:
            return x
        y = self.impl.process(x)
        if isinstance(self.impl, RNNoise):
            self.vad = self.impl.vad
            if self.gate is not None:
                y = self.gate.process(y, self.vad)
        return y
