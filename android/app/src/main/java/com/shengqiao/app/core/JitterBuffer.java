package com.shengqiao.app.core;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

public final class JitterBuffer {
    private final ArrayDeque<byte[]> packets = new ArrayDeque<>();
    private int baseFrames;
    private int extraFrames;
    private boolean buffering = true;
    private int expectedSequence = -1;
    private int underruns;
    private int lostFrames;
    private int skips;
    private final LongSupplier clock;
    private long stableSince;

    public JitterBuffer(int targetMs) {
        this(targetMs, System::nanoTime);
    }

    JitterBuffer(int targetMs, LongSupplier clock) {
        this.clock = clock;
        stableSince = clock.getAsLong();
        baseFrames = Math.max(1, targetMs / 10);
    }

    public synchronized void setTargetMs(int targetMs) {
        baseFrames = Math.max(1, targetMs / 10);
        extraFrames = 0;
        buffering = true;
        packets.clear();
        stableSince = clock.getAsLong();
    }

    public synchronized void reset() {
        packets.clear();
        buffering = true;
        expectedSequence = -1;
        stableSince = clock.getAsLong();
    }

    public synchronized void offer(byte[] packet) {
        int sequence = AudioPacket.sequence(packet);
        if (expectedSequence >= 0 && sequence != expectedSequence) {
            lostFrames += (sequence - expectedSequence) & 0x7fffffff;
        }
        expectedSequence = sequence + 1;
        packets.add(packet);
        int maximum = baseFrames + extraFrames + 30;
        if (packets.size() > maximum) {
            int keep = baseFrames + extraFrames + 5;
            while (packets.size() > keep) {
                packets.removeFirst();
                skips++;
            }
            buffering = true;
        }
    }

    public synchronized byte[] take() {
        if (buffering) {
            if (packets.size() < baseFrames + extraFrames) {
                return null;
            }
            buffering = false;
            stableSince = clock.getAsLong();
        }
        byte[] packet = packets.pollFirst();
        if (packet == null) {
            buffering = true;
            underruns++;
            extraFrames = Math.min(20, extraFrames + 2);
            stableSince = clock.getAsLong();
            return null;
        }
        if (clock.getAsLong() - stableSince >= 15_000_000_000L) {
            if (extraFrames > 0) extraFrames--;
            // Consume excess audio too; changing the target alone cannot reduce latency.
            if (packets.size() >= baseFrames + extraFrames) {
                byte[] next = packets.removeFirst();
                packet = AudioPacket.isPcm(packet) && AudioPacket.isPcm(next)
                        ? crossfade(packet, next) : next;
                skips++;
            }
            stableSince = clock.getAsLong();
        }
        return packet;
    }

    private static byte[] crossfade(byte[] old, byte[] next) {
        byte[] mixed = next.clone();
        int frames = Math.min(old.length - 8, next.length - 8) / 4;
        for (int i = 0; i < frames; i++) {
            float blend = frames > 1 ? i / (float) (frames - 1) : 1;
            for (int channel = 0; channel < 2; channel++) {
                int offset = 8 + i * 4 + channel * 2;
                int value = Math.round(AudioPacket.pcm16(old, offset) * (1 - blend)
                        + AudioPacket.pcm16(next, offset) * blend);
                mixed[offset] = (byte) value;
                mixed[offset + 1] = (byte) (value >> 8);
            }
        }
        return mixed;
    }

    public synchronized int bufferedMs() {
        return packets.size() * 10;
    }

    public synchronized int targetMs() {
        return (baseFrames + extraFrames) * 10;
    }

    public synchronized boolean isBuffering() {
        return buffering;
    }

    public synchronized int underruns() {
        return underruns;
    }

    public synchronized int lostFrames() {
        return lostFrames;
    }

    public synchronized int skips() {
        return skips;
    }
}
