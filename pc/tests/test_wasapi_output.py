import os
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from cffi import FFI
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
from capture import play_wasapi_stereo


class WasapiOutputTests(unittest.TestCase):
    def test_submits_only_written_frames_even_with_partial_writes(self):
        ffi = FFI()
        requested = []
        submitted = []
        buffers = []
        available = iter([120, 4800])

        def get_buffer(frames):
            requested.append(frames)
            memory = ffi.new('float[]', frames * 2)
            buffers.append(memory)
            return [memory]

        player = SimpleNamespace(_render_available_frames=lambda: next(available),
                                 _render_buffer=get_buffer, _render_release=submitted.append)
        samples = np.arange(960, dtype=np.float32).reshape(480, 2) / 960
        with patch.dict(sys.modules, {'soundcard.mediafoundation': SimpleNamespace(_ffi=ffi)}):
            play_wasapi_stereo(player, samples)

        self.assertEqual([120, 360], requested)
        self.assertEqual(requested, submitted)
        written = b''.join(bytes(ffi.buffer(buffer)) for buffer in buffers)
        self.assertEqual(samples.tobytes(), written)


if __name__ == '__main__':
    unittest.main()
