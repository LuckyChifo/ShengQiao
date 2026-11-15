import asyncio
import json
import os
import struct
import sys
import time
import unittest
from unittest.mock import AsyncMock, Mock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))
import capture
import opus_codec
import server


class MemoryTransport:
    def __init__(self):
        self.frames = []
        self.aborted = False
        self.backlog = 0

    def set_write_buffer_limits(self, **kwargs):
        pass

    def is_closing(self):
        return self.aborted

    def write(self, frame):
        self.frames.append(frame)

    def get_write_buffer_size(self):
        return self.backlog

    def abort(self):
        self.aborted = True

    async def drain(self):
        await asyncio.Future()

    @property
    def transport(self):
        return self


def unpack_frame(frame):
    code = frame[1] & 127
    offset = 2 if code < 126 else 4
    if frame[0] & 15 == server.OP_TEXT:
        return json.loads(frame[offset:])
    return struct.unpack_from('<BBHI', frame, offset)


class StreamSenderTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.app = server.App(server.parse_args(['--no-bonjour']), {}, None, None)

    async def client(self, **config):
        transport = MemoryTransport()
        client = server.WSClient(self.app, transport, 'test', 'Android')
        self.addAsyncCleanup(self.close, client)
        self.app.configure_stream(client, dict(configId=1, clientFeatures=server.STREAM_FEATURES, **config))
        await asyncio.sleep(0)
        return client, transport

    async def close(self, client):
        if client.stream_sender is not None:
            client.stream_sender.task.cancel()
            await asyncio.gather(client.stream_sender.task, return_exceptions=True)
        client.audio_pipeline.close()

    async def test_microphone_timeout_resumes_without_stealing_from_another_client(self):
        self.app.mic._thread = Mock()  # Keep real ownership and jitter state without opening a device.
        original, _ = await self.client(listen=False, mic=True)
        packet = struct.pack('<BBHI', 2, 0, 0, 7) + bytes(960)

        async def expire_owner():
            self.app.mic.last_feed = time.monotonic() - 11
            with patch('server.asyncio.sleep', new=AsyncMock(side_effect=[None, asyncio.CancelledError])):
                with self.assertRaises(asyncio.CancelledError):
                    await self.app.push_status()
            self.assertIsNone(self.app.mic.owner)

        await expire_owner()
        self.app.on_ws_binary(original, packet)
        self.assertIs(original, self.app.mic.owner)
        self.assertEqual(8, original.mic_seq)
        await expire_owner()
        self.app.configure_stream(original, dict(configId=2, mic=True))
        self.assertIs(original, self.app.mic.owner)

        await expire_owner()
        other, _ = await self.client(listen=False, mic=True)
        self.app.on_ws_binary(original, packet)
        self.app.configure_stream(original, dict(configId=3, mic=True))
        self.assertIs(other, self.app.mic.owner)
        self.app.configure_stream(other, dict(configId=2, mic=False))
        self.app.on_ws_binary(original, packet)
        self.assertIsNone(self.app.mic.owner)

    async def test_order_expiry_and_duplicate_config(self):
        client, tr = await self.client(latencyMode='low', bitrate=128)
        sender = client.stream_sender
        initial = client.audio_pipeline.stream_id
        self.app.configure_stream(client, dict(configId=1, latencyMode='standard', mic=True))
        self.assertFalse(client.mic)
        sender.offer(time.monotonic() - 0.2, bytes(1920), 1)
        sender.offer(time.monotonic(), bytes(1920), 1)
        await asyncio.sleep(0)
        frames = list(map(unpack_frame, tr.frames))
        self.assertEqual('stream_info', frames[0]['type'])
        self.assertEqual(initial + 1, frames[-3]['streamId'])
        self.assertEqual('status', frames[-2]['type'])
        self.assertEqual(initial + 1, frames[-2]['streamId'])
        self.assertEqual('low', frames[-2]['latencyMode'])
        self.assertEqual(0, frames[-1][3])
        self.assertEqual(1, client.dropped)
        old_stream = client.audio_pipeline.stream_id
        self.app.configure_stream(client, dict(configId=2, latencyMode='low', bitrate=128))
        self.assertEqual(old_stream, client.audio_pipeline.stream_id)
        self.app.configure_stream(client, dict(configId=3, latencyMode='standard', bitrate=0))
        sender.offer(time.monotonic(), bytes(1920), 0)
        await asyncio.sleep(0)
        last = list(map(unpack_frame, tr.frames[-3:]))
        self.assertEqual((old_stream + 1, 'standard', 'pcm'),
                         (last[0]['streamId'], last[0]['latencyMode'], last[0]['codec']))
        self.assertEqual((1, 0, 0, 0), last[2])

    async def test_ack_validation_wrap_and_timeout(self):
        client, tr = await self.client(latencyMode='low')
        sender = client.stream_sender
        stream = client.audio_pipeline.stream_id
        sender.ack_pending.extend((seq, time.monotonic()) for seq in [0xFFFFFFFF, 0, 1])
        for invalid in [(stream - 1, 1), (stream, 2), (stream, 0xFFFFFFFE), (stream, True)]:
            sender.ack(*invalid)
            self.assertEqual(3, len(sender.ack_pending))
        sender.ack(stream, 0)
        sender.ack(stream, 0)
        self.assertEqual([1], [item[0] for item in sender.ack_pending])
        sender.ack_pending[0] = (1, time.monotonic() - 1)
        sender.wake.set()
        await asyncio.wait_for(sender.task, 1)
        self.assertTrue(tr.aborted)

    async def test_blocked_client_stays_bounded_and_fast_client_continues(self):
        slow, stalled = await self.client(latencyMode='low')
        fast, writable = await self.client(latencyMode='standard')
        stalled.backlog = 9000
        slow.stream_sender.offer(time.monotonic(), bytes(1920), 1)
        await asyncio.sleep(0)
        for _ in range(10):
            slow.stream_sender.offer(time.monotonic(), bytes(1920), 1)
            fast.stream_sender.offer(time.monotonic(), bytes(1920), 1)
            await asyncio.sleep(0)
        self.assertLessEqual(len(slow.stream_sender.pcm), 4)
        self.assertGreater(slow.dropped, 0)
        self.assertEqual(10, len([f for f in writable.frames if f[0] & 15 == server.OP_BIN]))
        await asyncio.wait_for(slow.stream_sender.task, 1)
        self.assertTrue(stalled.aborted)
        self.assertFalse(writable.aborted)

    async def test_idle_stream_does_not_disconnect_for_missing_ack(self):
        client, tr = await self.client(latencyMode='low')
        sender = client.stream_sender
        sender.offer(time.monotonic(), bytes(1920), 1)
        await asyncio.sleep(0.60)
        self.assertFalse(tr.aborted)
        sender.ack(client.audio_pipeline.stream_id, 0)
        self.assertFalse(sender.ack_pending)

    async def test_unexpected_pipeline_failure_closes_only_affected_connection(self):
        failed, failed_transport = await self.client(latencyMode='standard')
        healthy, healthy_transport = await self.client(latencyMode='standard')
        with patch.object(failed.audio_pipeline, 'encode', side_effect=RuntimeError('invalid state')):
            failed.stream_sender.offer(time.monotonic(), bytes(1920), 0)
            healthy.stream_sender.offer(time.monotonic(), bytes(1920), 0)
            await asyncio.wait_for(failed.stream_sender.task, 1)
        self.assertTrue(failed_transport.aborted)
        self.assertFalse(healthy_transport.aborted)
        self.assertEqual((1, 0, 0, 0), unpack_frame(healthy_transport.frames[-1]))

    async def test_codec_failure_is_local(self):
        with patch.object(opus_codec, 'available', return_value=False):
            client, tr = await self.client(latencyMode='low', bitrate=64)
            self.assertEqual('low', client.audio_pipeline.actual_profile.mode)
            status = unpack_frame(tr.frames[-1])
            self.assertEqual('opus_unavailable', status['codecFallbackReason'])
            self.assertEqual('pcm', status['codec'])
        if not opus_codec.available():
            return
        first, first_tr = await self.client(latencyMode='low', bitrate=64)
        second, second_tr = await self.client(latencyMode='standard', bitrate=64)
        with patch.object(first.audio_pipeline.encoder, 'encode', side_effect=RuntimeError('broken')):
            for c in [first, second]:
                c.stream_sender.offer(time.monotonic(), bytes(1920), 1)
            await asyncio.sleep(0)
        self.assertEqual(1, unpack_frame(first_tr.frames[-1])[0])
        self.assertEqual(3, unpack_frame(second_tr.frames[-1])[0])
        self.assertEqual('encoder_error', unpack_frame(first_tr.frames[-2])['codecFallbackReason'])


class CaptureMailboxTests(unittest.TestCase):
    def test_stalled_event_loop_keeps_one_live_block_and_notifies_once(self):
        loop = Mock()
        delivered = Mock()
        discontinuity = Mock()
        stamps = iter([0, 0.01, 0.02, 1, 1.01, 1.02])
        mailbox = capture.CaptureMailbox(loop, delivered, discontinuity, clock=lambda: next(stamps))
        for i in range(5):
            mailbox.offer(i)
        loop.call_soon_threadsafe.assert_called_once()
        loop.call_soon_threadsafe.call_args.args[0]()
        delivered.assert_called_once_with(4, 1.01)
        discontinuity.assert_called_once_with(4)


if __name__ == '__main__':
    unittest.main()
