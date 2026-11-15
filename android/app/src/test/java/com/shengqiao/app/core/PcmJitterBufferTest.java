package com.shengqiao.app.core;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class PcmJitterBufferTest {
    private final AtomicLong clock = new AtomicLong();
    private final PcmJitterBuffer pcm = new PcmJitterBuffer(70, clock::get);
    private final short[] output = new short[960];
    private final byte[] frame = signal();

    private static byte[] signal() {
        byte[] frame = new byte[1928];
        for (int i = 8; i < frame.length; i += 2) {
            frame[i] = (byte) 0x10;
            frame[i + 1] = (byte) 0x27;
        }
        return frame;
    }

    private void fill(int count) {
        for (int i = 0; i < count; i++) pcm.offer(frame);
    }

    @Test
    public void lowStartupUnderrunAndStreamResetAreSeparate() {
        pcm.startStream(true);
        fill(2);
        pcm.render(1, output);
        assertTrue(pcm.isBuffering());
        assertEquals(0, pcm.underruns());
        fill(1);
        pcm.render(1, output);
        assertFalse(pcm.isBuffering());
        assertEquals(0, output[0]);
        assertEquals(10000, output[959]);
        for (int i = 0; i < 3; i++) pcm.render(1, output);
        assertEquals(10000, output[0]);
        assertEquals(0, output[959]);
        for (int i = 0; i < 50; i++) pcm.render(1, output);
        assertArrayEquals(new short[960], output);
        assertEquals(1, pcm.underruns());
        assertEquals(40, pcm.targetMs());
        pcm.startStream(false);
        assertEquals(70, pcm.targetMs());
        assertEquals(0, pcm.bufferedMs());
        assertEquals(0, pcm.underruns());
        pcm.startStream(true);
        assertEquals(30, pcm.targetMs());
    }

    @Test
    public void recoveryIsBoundedAndSustainedLossTriggersFallback() {
        pcm.startStream(true);
        fill(60);
        assertTrue(pcm.bufferedMs() <= 60);
        assertTrue(pcm.skips() > 0);
        for (int i = 0; i < 3; i++) {
            fill(pcm.targetMs() / 10);
            for (int j = 0; j < 12; j++) {
                pcm.render(1, output);
                clock.addAndGet(10_000_000);
            }
        }
        assertEquals(3, pcm.underruns());
        assertEquals(60, pcm.targetMs());
        assertTrue(pcm.isUnstable());
        pcm.startStream(true);
        assertFalse(pcm.isUnstable());
    }

    @Test
    public void oldPcmExpiresEvenWhenTheQueueIsShort() {
        pcm.startStream(true);
        fill(3);
        clock.addAndGet(500_000_000);
        fill(1);
        pcm.render(1, output);
        assertEquals(10, pcm.bufferedMs());
        assertEquals(1, pcm.skips());
        assertTrue(pcm.isBuffering());
        assertArrayEquals(new short[960], output);
    }

    @Test
    public void stablePlaybackConsumesExtraAndCorrectsBothDirectionsOfClockDrift() {
        for (double ratio : new double[]{0.9997, 1.0003}) {
            pcm.startStream(true);
            fill(3);
            for (int i = 0; i < 5; i++) pcm.render(1, output);
            assertEquals(40, pcm.targetMs());
            fill(5);
            int offered = 0;
            for (int i = 1; i <= 30_000; i++) {
                int due = (int) (i * ratio);
                fill(due - offered);
                offered = due;
                pcm.render(1, output);
                clock.addAndGet(10_000_000);
            }
            assertEquals(30, pcm.targetMs());
            assertEquals(1, pcm.underruns());
            assertTrue(pcm.bufferedMs() >= 10 && pcm.bufferedMs() <= 40);
            assertTrue(pcm.skips() <= 2);
        }
    }
}
