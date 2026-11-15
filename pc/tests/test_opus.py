import ctypes
import math
import os
import struct
import sys
import unittest
from itertools import product

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
import opus_codec


class OpusCodecTests(unittest.TestCase):
    def setUp(self):
        if not opus_codec.available():
            self.skipTest('Opus library is not available')
        self.lib = opus_codec.load_opus()
        self.lib.opus_decoder_create.restype = ctypes.c_void_p
        self.lib.opus_decoder_create.argtypes = [ctypes.c_int, ctypes.c_int,
                                                 ctypes.POINTER(ctypes.c_int)]
        self.lib.opus_decode.restype = ctypes.c_int
        self.lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int,
                                               ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
        self.lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]

    def test_all_bitrates_encode_and_decode(self):
        for bitrate, application in product(opus_codec.BITRATES,
                                            (opus_codec.OPUS_APPLICATION_VOIP,
                                             opus_codec.OPUS_APPLICATION_RESTRICTED_LOWDELAY)):
            with self.subTest(bitrate=bitrate, application=application):
                encoder = opus_codec.OpusEncoder(bitrate, application)
                self.assertEqual(120 if application == 2051 else 312, encoder.lookahead)
                actual_bitrate = ctypes.c_int()
                self.assertEqual(0, self.lib.opus_encoder_ctl(encoder.handle, 4003,
                                                              ctypes.byref(actual_bitrate)))
                self.assertEqual(bitrate * 1000, actual_bitrate.value)
                error = ctypes.c_int()
                decoder = self.lib.opus_decoder_create(48000, 2, ctypes.byref(error))
                self.assertTrue(decoder)
                self.assertEqual(0, error.value)
                try:
                    decoded_sizes = []
                    for frame in range(30):
                        samples = [int(12000 * math.sin(2 * math.pi * 440 * (frame * 480 + i) / 48000))
                                   for i in range(480)]
                        pcm = struct.pack('<' + 'h' * 960, *(v for value in samples for v in (value, value)))
                        encoded = encoder.encode(pcm)
                        self.assertGreater(len(encoded), 0)
                        self.assertLess(len(encoded), 1920)
                        source = ctypes.create_string_buffer(encoded)
                        output = ctypes.create_string_buffer(1920)
                        size = self.lib.opus_decode(decoder, source, len(encoded),
                                                          output, 480, 0)
                        self.assertEqual(480, size)
                        decoded_sizes.append(size)
                    self.assertEqual([480] * 30, decoded_sizes)
                finally:
                    encoder.close()
                    self.lib.opus_decoder_destroy(decoder)

    def test_rejects_unknown_bitrate(self):
        with self.assertRaises(ValueError):
            opus_codec.OpusEncoder(100)

    def test_rejects_invalid_pcm_length(self):
        encoder = opus_codec.OpusEncoder(128)
        try:
            for byte_count in [0, 1919, 1921, 3840]:
                with self.assertRaises(ValueError):
                    encoder.encode(bytes(byte_count))
        finally:
            encoder.close()


if __name__ == '__main__':
    unittest.main()
