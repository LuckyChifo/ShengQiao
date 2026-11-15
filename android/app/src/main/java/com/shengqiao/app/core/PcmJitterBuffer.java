package com.shengqiao.app.core;

import java.util.function.LongSupplier;

/** Stores decoded stereo PCM. The decoder's reusable output is copied into the ring. */
public final class PcmJitterBuffer {
    private static final int BLOCK = 480;
    private static final int FADE = 96;
    private static final int CAPACITY = 66 * BLOCK;
    private final short[] pcm = new short[CAPACITY * 2];
    private final long[] receivedAt = new long[CAPACITY / BLOCK];
    private final short[] previous = new short[2];
    private final LongSupplier clock;
    private int head;
    private int size;
    private double fraction;
    private double average;
    private double speed = 1;
    private int standardMs;
    private int extra;
    private boolean low;
    private boolean buffering = true;
    private boolean splice = true;
    private long stableAt;
    private int underruns;
    private int recentUnderrunsSinceRoute;
    private int skips;
    private int gaps;
    private boolean unstable;
    private final long[] recentUnderruns = new long[3];

    public PcmJitterBuffer(int standardMs) {
        this(standardMs, System::nanoTime);
    }

    PcmJitterBuffer(int standardMs, LongSupplier clock) {
        this.standardMs = standardMs;
        this.clock = clock;
    }

    public synchronized void startStream(boolean low) {
        this.low = low;
        underruns = skips = gaps = 0;
        recentUnderrunsSinceRoute = 0;
        unstable = false;
        rebuffer();
        previous[0] = previous[1] = 0;
    }

    public synchronized void setStandardMs(int value) {
        if (standardMs == value) return;
        standardMs = value;
        if (!low) rebuffer();
    }

    public synchronized void resetForRoute() {
        int target = targetBlocks() * BLOCK;
        discardTo(target);
        buffering = true;
        fraction = 0;
        speed = 1;
        splice = true;
        previous[0] = previous[1] = 0;
        unstable = false;
        recentUnderrunsSinceRoute = 0;
    }

    private void rebuffer() {
        size = head = extra = 0;
        fraction = 0;
        speed = 1;
        buffering = splice = true;
        stableAt = clock.getAsLong();
    }

    public synchronized void gap(int frames) {
        gaps = (int) Math.min(Integer.MAX_VALUE, (long) gaps + frames);
    }

    void offer(byte[] packet) {
        offer(packet, clock.getAsLong());
    }

    public synchronized void offer(byte[] packet, long receivedAtNanos) {
        int target = targetBlocks();
        int limit = low ? Math.min(9, target + 3) : target + 30;
        if (size + BLOCK > limit * BLOCK) {
            discardTo((target + (low ? 1 : 5)) * BLOCK);
        }
        int tail = (head + size) % CAPACITY;
        receivedAt[tail / BLOCK] = receivedAtNanos;
        for (int i = 0; i < BLOCK; i++) {
            int offset = tail * 2;
            pcm[offset] = AudioPacket.pcm16(packet, 8 + i * 4);
            pcm[offset + 1] = AudioPacket.pcm16(packet, 10 + i * 4);
            if (++tail == CAPACITY) tail = 0;
        }
        size += BLOCK;
    }

    public synchronized void render(float gain, short[] output) {
        long now = clock.getAsLong();
        discardExpired(now);
        int target = targetBlocks() * BLOCK;
        if (buffering && size >= target) {
            buffering = false;
            stableAt = now;
            average = size;
            speed = 1;
        }
        if (!buffering && size < BLOCK) {
            buffering = true;
            fraction = 0;
            if (size > 0) advance(size);
            recentUnderruns[underruns % recentUnderruns.length] = now;
            underruns++;
            recentUnderrunsSinceRoute++;
            extra = Math.min(low ? 3 : 20, extra + (low ? 1 : 2));
            if (low && recentUnderrunsSinceRoute >= 3 && extra == 3
                    && now - recentUnderruns[(underruns - 3) % 3] <= 10_000_000_000L) {
                unstable = true;
            }
        }
        if (!buffering && extra > 0 && now - stableAt >= (low ? 10L : 15L) * 1_000_000_000L) {
            extra--;
            target = targetBlocks() * BLOCK;
            if (size >= target + BLOCK) discardTo(size - BLOCK);
            stableAt = now;
        }
        if (!buffering) {
            average += (size - average) * 0.01;
            // The sampled water level includes the block about to be played.
            double correction = (average - target - BLOCK * 0.25) * 0.00001;
            speed += (Math.max(-0.005, Math.min(0.005, correction)) + 1 - speed) * 0.05;
        }
        double cursor = fraction;
        for (int i = 0; i < BLOCK; i++) {
            int index = Math.min((int) cursor, size - 1);
            float blend = i < FADE ? i / (float) FADE : 1;
            for (int channel = 0; channel < 2; channel++) {
                float value;
                if (buffering) {
                    value = previous[channel] * (1 - blend);
                } else {
                    int a = (head + index) % CAPACITY * 2 + channel;
                    int b = index + 1 < size ? (a + 2) % pcm.length : a;
                    value = (float) (pcm[a] + (pcm[b] - pcm[a]) * (cursor - (int) cursor)) * gain;
                    if (splice) value = value * blend + previous[channel] * (1 - blend);
                }
                output[i * 2 + channel] = (short) Math.max(-32768, Math.min(32767, Math.round(value)));
            }
            cursor += speed;
        }
        if (!buffering) {
            int consumed = Math.min((int) cursor, size);
            fraction = cursor >= size ? 0 : cursor - consumed;
            advance(consumed);
        }
        splice = buffering;
        previous[0] = output[output.length - 2];
        previous[1] = output[output.length - 1];
    }

    private void discardExpired(long now) {
        int before = size;
        long maxAge = low ? 150_000_000L : 650_000_000L;
        while (size > 0 && now - receivedAt[head / BLOCK] > maxAge) {
            advance(Math.min(size, BLOCK - head % BLOCK));
        }
        if (size < before) {
            fraction = 0;
            skips++;
            splice = true;
        }
    }

    private void discardTo(int frames) {
        if (size <= frames) return;
        advance(size - frames);
        fraction = 0;
        skips++;
        splice = true;
    }

    private void advance(int frames) {
        head = (head + frames) % CAPACITY;
        size -= frames;
    }

    private int targetBlocks() {
        return (low ? 3 : Math.max(1, Math.min(15, standardMs / 10))) + extra;
    }

    public synchronized int bufferedMs() { return size / 48; }
    public synchronized int targetMs() { return targetBlocks() * 10; }
    public synchronized boolean isBuffering() { return buffering; }
    public synchronized int underruns() { return underruns; }
    public synchronized int lostFrames() { return gaps; }
    public synchronized int skips() { return skips; }
    public synchronized boolean isUnstable() { return unstable; }
}
