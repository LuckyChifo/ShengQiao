import ctypes
import os
import sys
import threading

BITRATES = (64, 128, 256, 320)
SAMPLE_RATE = 48000
CHANNELS = 2
FRAME_SAMPLES = 480
PCM_BYTES = FRAME_SAMPLES * CHANNELS * 2
OPUS_APPLICATION_VOIP = 2048
OPUS_APPLICATION_RESTRICTED_LOWDELAY = 2051
APPLICATIONS = (OPUS_APPLICATION_VOIP, OPUS_APPLICATION_RESTRICTED_LOWDELAY)
OPUS_SET_BITRATE = 4002
OPUS_GET_LOOKAHEAD = 4027

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


def load_opus():
    global _lib, _lib_error
    with _lib_lock:
        if _lib is not None or _lib_error is not None:
            return _lib
        names = ['opus.dll'] if sys.platform == 'win32' else ['libopus.so.0', 'libopus.so', 'libopus.dylib']
        for directory in _lib_dirs():
            for name in names:
                path = os.path.join(directory, name)
                if not os.path.exists(path):
                    continue
                try:
                    lib = ctypes.CDLL(path)
                    lib.opus_encoder_create.restype = ctypes.c_void_p
                    lib.opus_encoder_create.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                                        ctypes.POINTER(ctypes.c_int)]
                    lib.opus_encode.restype = ctypes.c_int
                    lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int,
                                                      ctypes.c_void_p, ctypes.c_int]
                    lib.opus_encoder_ctl.restype = ctypes.c_int
                    lib.opus_encoder_ctl.argtypes = [ctypes.c_void_p, ctypes.c_int]
                    lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
                    lib.opus_encoder_destroy.restype = None
                    _lib = lib
                    return _lib
                except OSError as e:
                    _lib_error = '%s: %s' % (path, e)
        if _lib_error is None:
            _lib_error = '找不到 Opus 库'
        return None


def available():
    return load_opus() is not None


class OpusEncoder:
    def __init__(self, bitrate_kbps, application=OPUS_APPLICATION_VOIP):
        if bitrate_kbps not in BITRATES:
            raise ValueError('unsupported Opus bitrate')
        if application not in APPLICATIONS:
            raise ValueError('unsupported Opus application')
        self.lib = load_opus()
        if self.lib is None:
            raise RuntimeError(_lib_error)
        self.application = application
        self.lookahead = None
        error = ctypes.c_int()
        self.handle = self.lib.opus_encoder_create(SAMPLE_RATE, CHANNELS, application,
                                                   ctypes.byref(error))
        if not self.handle or error.value != 0:
            raise RuntimeError('Opus 编码器初始化失败：%d' % error.value)
        result = self.lib.opus_encoder_ctl(self.handle, OPUS_SET_BITRATE,
                                           ctypes.c_int(bitrate_kbps * 1000))
        if result != 0:
            self.close()
            raise RuntimeError('Opus 码率设置失败：%d' % result)
        lookahead = ctypes.c_int()
        result = self.lib.opus_encoder_ctl(self.handle, OPUS_GET_LOOKAHEAD,
                                           ctypes.byref(lookahead))
        if result != 0:
            self.close()
            raise RuntimeError('Opus lookahead 查询失败：%d' % result)
        self.lookahead = lookahead.value

    def encode(self, pcm):
        if not self.handle:
            raise RuntimeError('Opus 编码器已关闭')
        try:
            view = memoryview(pcm)
        except TypeError:
            raise TypeError('PCM 输入必须是 bytes-like 对象') from None
        if view.nbytes != PCM_BYTES:
            raise ValueError('PCM 输入必须为 %d 字节（480 帧双声道 int16），实际为 %d 字节'
                             % (PCM_BYTES, view.nbytes))
        data = pcm if isinstance(pcm, bytes) else view.tobytes()
        source = ctypes.create_string_buffer(data)
        output = ctypes.create_string_buffer(1500)
        size = self.lib.opus_encode(self.handle, source, FRAME_SAMPLES, output, len(output))
        if size < 0:
            raise RuntimeError('Opus 编码失败：%d' % size)
        return output.raw[:size]

    def close(self):
        if getattr(self, 'handle', None):
            self.lib.opus_encoder_destroy(self.handle)
            self.handle = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass
