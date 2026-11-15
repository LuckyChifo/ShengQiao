"""Bounded, ordered audio transport for one negotiated client."""
import asyncio
from collections import deque
import struct
import time

import audio_pipeline


class StreamSender:
    """One ordered writer and bounded pre-encode PCM queue per negotiated connection."""

    def __init__(self, client, binary_frame, log):
        self.client = client
        self.binary_frame = binary_frame
        self.log = log
        self.pipeline = client.audio_pipeline
        self.pcm = deque()
        self.controls = deque()
        self.ack_pending = deque()
        self.min_rtt = None
        self.prior_unconfirmed_at = None
        self.last_audio_at = None
        self.discontinuous = False
        self.wake = asyncio.Event()
        self.client.writer.transport.set_write_buffer_limits(high=8192, low=2048)
        self.task = asyncio.create_task(self.run())

    @property
    def low(self):
        profile = self.pipeline.actual_profile
        return profile is not None and profile.mode == audio_pipeline.LOW

    def control(self, frame):
        if len(self.controls) >= 32:
            self.client.writer.transport.abort()
            return
        self.controls.append(frame)
        self.wake.set()

    def clear_audio(self):
        self.pcm.clear()
        self.ack_pending.clear()
        self.prior_unconfirmed_at = None
        self.last_audio_at = None
        self.discontinuous = False

    def announce(self, info):
        self.pcm.clear()
        self.discontinuous = False
        self.replace_ack_stream()
        self.client.send_json(info)
        self.client.send_json(self.client.app.mic_status_for(self.client))

    def discontinuity(self):
        self.client.dropped += len(self.pcm)
        self.pcm.clear()
        self.discontinuous = True

    def offer(self, produced_at, pcm, flags):
        self.pcm.append((produced_at, pcm, flags))
        limit, keep = (4, 2) if self.low else (15, 7)
        if len(self.pcm) > limit:
            while len(self.pcm) > keep:
                self.pcm.popleft()
                self.client.dropped += 1
            self.discontinuous = True
        self.wake.set()

    def observe_rtt(self, rtt):
        if type(rtt) is int and 0 <= rtt <= 2000:
            self.min_rtt = rtt if self.min_rtt is None else min(self.min_rtt, rtt)

    def ack(self, stream, sequence):
        if (not self.low or type(stream) is not int or stream != self.pipeline.stream_id
                or type(sequence) is not int or not 0 <= sequence <= 0xFFFFFFFF
                or not self.ack_pending):
            return
        offset = (sequence - self.ack_pending[0][0]) & 0xFFFFFFFF
        if offset < len(self.ack_pending):
            for _ in range(offset + 1):
                self.ack_pending.popleft()
            self.prior_unconfirmed_at = None

    def replace_ack_stream(self):
        # A later in-order ACK proves that older bytes have drained too.
        if self.low and self.prior_unconfirmed_at is None and self.ack_pending:
            self.prior_unconfirmed_at = self.ack_pending[0][1]
        if not self.low:
            self.prior_unconfirmed_at = None
        self.ack_pending.clear()

    def oldest_unconfirmed(self):
        if self.prior_unconfirmed_at is not None:
            return self.prior_unconfirmed_at
        return self.ack_pending[0][1] if self.ack_pending else None

    def diagnostics(self):
        now = time.monotonic()
        unconfirmed = self.oldest_unconfirmed()
        return {
            'sendQueueMs': len(self.pcm) * 10,
            'sendOldestMs': round((now - self.pcm[0][0]) * 1000, 1) if self.pcm else 0,
            'unconfirmedMs': round((now - unconfirmed) * 1000, 1) if unconfirmed is not None else None,
        }

    def ack_timeout(self):
        return max(0.5, 0.2 + 2 * (self.min_rtt or 0) / 1000)

    async def run(self):
        writer = self.client.writer
        try:
            while not writer.transport.is_closing():
                now = time.monotonic()
                oldest = self.oldest_unconfirmed()
                if not self.pcm and self.last_audio_at is not None and now - self.last_audio_at >= 0.25:
                    oldest = None
                if oldest is not None and now - oldest >= self.ack_timeout():
                    raise asyncio.TimeoutError('audio ACK stalled')
                if self.controls:
                    self.client.write_frame(self.controls.popleft())
                elif self.pcm:
                    stamp, pcm, flags = self.pcm.popleft()
                    if now - stamp > (0.060 if self.low else 0.200):
                        self.client.dropped += 1
                        self.discontinuous = True
                        continue
                    if self.discontinuous:
                        # Keep the fresh suffix. Its first block starts a new encoder history.
                        self.replace_ack_stream()
                        self.discontinuous = False
                        self.client.send_json(self.pipeline.restart())
                        self.client.send_json(self.client.app.mic_status_for(self.client))
                    info, packet = self.pipeline.encode(pcm, flags)
                    if info is not None:
                        self.replace_ack_stream()
                        self.client.send_json(info)
                        self.client.send_json(self.client.app.mic_status_for(self.client))
                    while self.controls:
                        self.client.write_frame(self.controls.popleft())
                    self.client.write_frame(self.binary_frame(packet))
                    self.client.sent += 1
                    if self.low:
                        if len(self.ack_pending) >= 512:
                            raise asyncio.TimeoutError('audio ACK ledger full')
                        self.ack_pending.append((struct.unpack_from('<I', packet, 4)[0], time.monotonic()))
                        self.last_audio_at = self.ack_pending[-1][1]
                else:
                    self.wake.clear()
                    if oldest is None:
                        await self.wake.wait()
                    else:
                        remaining = self.ack_timeout() - (now - oldest)
                        try:
                            await asyncio.wait_for(self.wake.wait(), max(0.001, remaining))
                        except asyncio.TimeoutError:
                            pass
                    continue
                if writer.transport.get_write_buffer_size() >= 8192:
                    oldest = self.oldest_unconfirmed()
                    timeout = self.ack_timeout() if self.low else 1.0
                    if oldest is not None:
                        timeout -= time.monotonic() - oldest
                    await asyncio.wait_for(writer.drain(), max(0.001, timeout))
        except (asyncio.TimeoutError, ConnectionError, OSError) as error:
            self.log('音频连接恢复 %s：%s' % (self.client.ip, error or '发送超时'))
            writer.transport.abort()
        except Exception as error:
            # Terminate the session so its receive loop releases microphone and
            # encoder ownership instead of leaving a connected, silent client.
            self.log('音频发送失败 %s：%r' % (self.client.ip, error))
            writer.transport.abort()
