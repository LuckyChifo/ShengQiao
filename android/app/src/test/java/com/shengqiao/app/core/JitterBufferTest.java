package com.shengqiao.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JitterBufferTest {
    @Test
    public void stablePlaybackActuallyDrainsAdaptiveLatency() {
        long[] now = {0};
        JitterBuffer buffer = new JitterBuffer(40, () -> now[0]);
        for (int i = 0; i < 4; i++) buffer.offer(packet(i));
        for (int i = 0; i < 4; i++) buffer.take();
        assertNull(buffer.take());
        assertEquals(60, buffer.targetMs());
        for (int i = 4; i < 10; i++) buffer.offer(packet(i));
        buffer.take();
        buffer.offer(packet(10));
        now[0] = 15_000_000_001L;
        assertNotNull(buffer.take());
        assertEquals(50, buffer.targetMs());
        assertEquals(40, buffer.bufferedMs());
        assertEquals(1, buffer.skips());
    }

    private static byte[] packet(int sequence) {
        byte[] packet = new byte[1928];
        packet[0] = 1;
        packet[4] = (byte) sequence;
        packet[5] = (byte) (sequence >>> 8);
        packet[6] = (byte) (sequence >>> 16);
        packet[7] = (byte) (sequence >>> 24);
        return packet;
    }

    @Test
    public void waitsForTargetThenPlaysInOrder() {
        JitterBuffer buffer = new JitterBuffer(40);
        buffer.offer(packet(10));
        buffer.offer(packet(11));
        buffer.offer(packet(12));
        assertNull(buffer.take());
        buffer.offer(packet(13));
        assertEquals(10, AudioPacket.sequence(buffer.take()));
        assertEquals(11, AudioPacket.sequence(buffer.take()));
        assertEquals(40, buffer.targetMs());
    }

    @Test
    public void underflowAddsAdaptiveHeadroom() {
        JitterBuffer buffer = new JitterBuffer(40);
        for (int i = 0; i < 4; i++) {
            buffer.offer(packet(i));
        }
        assertNotNull(buffer.take());
        assertNotNull(buffer.take());
        assertNotNull(buffer.take());
        assertNotNull(buffer.take());
        assertNull(buffer.take());
        assertEquals(1, buffer.underruns());
        assertEquals(60, buffer.targetMs());
    }

    @Test
    public void burstDropsOldAudioToBoundLatency() {
        JitterBuffer buffer = new JitterBuffer(40);
        for (int i = 0; i <= 60; i++) {
            buffer.offer(packet(i));
        }
        assertTrue(buffer.bufferedMs() <= 90);
        assertTrue(buffer.skips() > 0);
        assertEquals(52, AudioPacket.sequence(buffer.take()));
    }

    @Test
    public void missingSequenceIsReported() {
        JitterBuffer buffer = new JitterBuffer(20);
        buffer.offer(packet(5));
        buffer.offer(packet(8));
        assertEquals(2, buffer.lostFrames());
    }

    @Test
    public void opusPacketsAreNotCrossfadedAsPcm() {
        long[] now = {0};
        JitterBuffer buffer = new JitterBuffer(10, () -> now[0]);
        byte[] first = opusPacket(1, (byte) 40);
        byte[] second = opusPacket(2, (byte) 80);
        buffer.offer(first);
        buffer.offer(second);
        assertEquals(1, AudioPacket.sequence(buffer.take()));
        buffer.offer(opusPacket(3, (byte) 120));
        now[0] = 15_000_000_001L;
        byte[] result = buffer.take();
        assertEquals(3, AudioPacket.sequence(result));
        assertEquals(120, result[8]);
    }

    private static byte[] opusPacket(int sequence, byte payload) {
        byte[] packet = new byte[]{3, 0, 0, 0, 0, 0, 0, 0, payload, payload};
        packet[4] = (byte) sequence;
        return packet;
    }
}
