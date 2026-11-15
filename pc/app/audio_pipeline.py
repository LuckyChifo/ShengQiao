import struct

import opus_codec

STANDARD = 'standard'
LOW = 'low'
MODES = (STANDARD, LOW)


class StreamProfile:
    def __init__(self, mode, bitrate):
        if mode not in MODES:
            raise ValueError('unsupported latency mode')
        if type(bitrate) is not int or bitrate not in (0,) + opus_codec.BITRATES:
            raise ValueError('unsupported audio bitrate')
        self.mode = mode
        self.bitrate = bitrate

    @property
    def codec(self):
        return 'opus' if self.bitrate else 'pcm'

    @property
    def opus_application(self):
        if not self.bitrate:
            return None
        return (opus_codec.OPUS_APPLICATION_RESTRICTED_LOWDELAY if self.mode == LOW
                else opus_codec.OPUS_APPLICATION_VOIP)

    def __eq__(self, other):
        if not isinstance(other, StreamProfile):
            return NotImplemented
        return self.mode == other.mode and self.bitrate == other.bitrate


class ClientAudioPipeline:
    def __init__(self):
        self.config_id = 0
        self.stream_config_id = None
        self.stream_id = 0
        self.sequence = 0
        self.requested_profile = None
        self.actual_profile = None
        self.encoder = None
        self.codec_delay_samples = 0
        self.codec_fallback_reason = None
        self.last_codec_error = None

    def configure(self, profile, config_id):
        if not isinstance(profile, StreamProfile):
            raise TypeError('profile must be a StreamProfile')
        if type(config_id) is not int or config_id < 1:
            raise ValueError('invalid configId')
        changed = profile != self.requested_profile
        self.config_id = config_id
        self.requested_profile = profile
        if not changed and self.actual_profile is not None:
            return None
        self.codec_fallback_reason = None
        self.last_codec_error = None
        return self._start_requested()

    def restart(self):
        if self.actual_profile is None:
            raise RuntimeError('audio stream is not configured')
        profile = self.actual_profile
        try:
            return self._start_stream(profile)
        except Exception as error:
            if not profile.bitrate:
                raise
            return self._start_pcm_fallback('encoder_error', error)

    def encode(self, pcm, flags=0):
        data = self._pcm_bytes(pcm)
        if self.actual_profile is None:
            raise RuntimeError('audio stream is not configured')
        if not self.actual_profile.bitrate:
            return None, self._packet(1, data, flags)
        try:
            encoded = self.encoder.encode(data)
        except Exception as error:
            info = self._start_pcm_fallback('encoder_error', error)
            return info, self._packet(1, data, flags)
        return None, self._packet(3, encoded, flags)

    def status(self):
        if self.actual_profile is None:
            raise RuntimeError('audio stream is not configured')
        return {
            'configId': self.config_id,
            'requestedLatencyMode': self.requested_profile.mode,
            'latencyMode': self.actual_profile.mode,
            'streamId': self.stream_id,
            'codec': self.actual_profile.codec,
            'bitrate': self.actual_profile.bitrate,
            'frameMs': opus_codec.FRAME_SAMPLES * 1000 // opus_codec.SAMPLE_RATE,
            'codecDelayMs': self.codec_delay_samples * 1000 / opus_codec.SAMPLE_RATE,
            'fallbackReason': None,
            'codecFallbackReason': self.codec_fallback_reason,
        }

    def close(self):
        if self.encoder is not None:
            self.encoder.close()
        self.encoder = None
        self.stream_config_id = None
        self.sequence = 0
        self.requested_profile = None
        self.actual_profile = None
        self.codec_delay_samples = 0
        self.codec_fallback_reason = None
        self.last_codec_error = None

    def _start_requested(self):
        profile = self.requested_profile
        if profile.bitrate and not opus_codec.available():
            return self._start_pcm_fallback('opus_unavailable', None)
        try:
            return self._start_stream(profile)
        except Exception as error:
            if not profile.bitrate:
                raise
            return self._start_pcm_fallback('encoder_error', error)

    def _start_pcm_fallback(self, reason, error):
        self.codec_fallback_reason = reason
        self.last_codec_error = None if error is None else str(error)
        profile = StreamProfile(self.requested_profile.mode, 0)
        return self._start_stream(profile)

    def _start_stream(self, profile):
        encoder = None
        if profile.bitrate:
            encoder = opus_codec.OpusEncoder(profile.bitrate, profile.opus_application)
        previous = self.encoder
        self.encoder = encoder
        if previous is not None:
            previous.close()
        self.actual_profile = profile
        self.stream_id += 1
        self.stream_config_id = self.config_id
        self.sequence = 0
        self.codec_delay_samples = encoder.lookahead if encoder is not None else 0
        return self._stream_info()

    def _stream_info(self):
        profile = self.actual_profile
        return {
            'type': 'stream_info',
            'streamId': self.stream_id,
            'configId': self.stream_config_id,
            'latencyMode': profile.mode,
            'codec': profile.codec,
            'bitrate': profile.bitrate,
            'sampleRate': opus_codec.SAMPLE_RATE,
            'channels': opus_codec.CHANNELS,
            'frameSamples': opus_codec.FRAME_SAMPLES,
            'firstSequence': 0,
            'opusApplication': profile.opus_application,
            'codecDelaySamples': self.codec_delay_samples,
        }

    def _packet(self, codec, payload, flags):
        sequence = self.sequence
        self.sequence = (sequence + 1) & 0xFFFFFFFF
        return struct.pack('<BBHI', codec, flags, 0, sequence) + payload

    @staticmethod
    def _pcm_bytes(pcm):
        try:
            view = memoryview(pcm)
        except TypeError:
            raise TypeError('PCM input must be bytes-like') from None
        if view.nbytes != opus_codec.PCM_BYTES:
            raise ValueError('invalid PCM input size')
        return pcm if isinstance(pcm, bytes) else view.tobytes()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass
