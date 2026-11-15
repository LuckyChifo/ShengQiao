package com.shengqiao.app;

import com.shengqiao.app.core.AudioPacket;
import com.shengqiao.app.core.OpusDecoder;
import com.shengqiao.app.core.PcmJitterBuffer;
import com.shengqiao.app.core.StreamConfig;

import java.util.ArrayDeque;

final class AudioStreamReceiver extends Thread {
    interface Failure {
        void onFailure(long revision, String reason);
    }

    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final PcmJitterBuffer pcm;
    private final Failure failure;
    private long revision;
    private StreamConfig stream;
    private boolean accepting;
    private boolean closed;

    AudioStreamReceiver(PcmJitterBuffer pcm, Failure failure) {
        super("shengqiao-decode");
        this.pcm = pcm;
        this.failure = failure;
    }

    synchronized void reset(long revision, StreamConfig stream, boolean accepting) {
        this.revision = revision;
        this.stream = stream;
        this.accepting = accepting;
        queue.clear();
        pcm.startStream(stream != null && stream.low);
        notifyAll();
    }

    void offer(byte[] packet) {
        long failedRevision;
        synchronized (this) {
            if (!accepting || closed) return;
            boolean low = stream != null && stream.low;
            if (queue.size() < (low ? 6 : 15)) {
                queue.addLast(new Frame(packet, System.nanoTime()));
                notifyAll();
                return;
            }
            failedRevision = revision;
        }
        fail(failedRevision, "processing_overload");
    }

    @Override
    public void run() {
        OpusDecoder decoder = null;
        long currentRevision = -1;
        int expected = 0;
        int currentCodec = 0;
        try {
            while (true) {
                Frame frame;
                long nextRevision;
                StreamConfig config;
                synchronized (this) {
                    while (!closed && currentRevision == revision && queue.isEmpty()) wait();
                    if (closed) break;
                    nextRevision = revision;
                    config = stream;
                    frame = queue.pollFirst();
                }
                if (nextRevision != currentRevision) {
                    if (decoder != null) decoder.close();
                    decoder = null;
                    currentCodec = 0;
                    currentRevision = nextRevision;
                    expected = 0;
                }
                if (frame == null) continue;
                if (System.nanoTime() - frame.receivedAt > (config != null && config.low
                        ? 60_000_000L : 150_000_000L)) {
                    fail(nextRevision, "processing_overload");
                    continue;
                }
                byte[] data = frame.packet;
                boolean opus = AudioPacket.isOpus(data);
                if (!opus && (!AudioPacket.isPcm(data) || data.length != 1928)) continue;
                int codec = data[0];
                int sequence = AudioPacket.sequence(data);
                // Older servers use a global sequence and can change codec without a stream boundary.
                if (config == null && codec != currentCodec) {
                    if (decoder != null) decoder.close();
                    decoder = null;
                    currentCodec = codec;
                    expected = sequence;
                }
                long gap = Integer.toUnsignedLong(sequence - expected);
                if (gap >= 0x80000000L) continue;
                if (gap > 0) {
                    synchronized (this) {
                        if (revision == nextRevision && accepting) pcm.gap((int) gap);
                    }
                }
                try {
                    if (opus && decoder == null) decoder = new OpusDecoder();
                    if (gap > 3 && config != null) {
                        fail(nextRevision, "network_unstable");
                        continue;
                    }
                    if (opus && gap > 3) {
                        decoder.close();
                        decoder = new OpusDecoder();
                    } else if (opus) {
                        for (int i = 0; i < gap; i++) {
                            byte[] concealed = decoder.concealLoss();
                            if (!pcm.isBuffering()) publish(nextRevision, concealed, frame.receivedAt);
                        }
                    }
                    publish(nextRevision, opus ? decoder.decode(data) : data, frame.receivedAt);
                    expected = sequence + 1;
                } catch (RuntimeException | LinkageError error) {
                    fail(nextRevision, opus ? "decoder_error" : "processing_overload");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (decoder != null) decoder.close();
        }
    }

    private synchronized void publish(long revision, byte[] samples, long receivedAt) {
        if (this.revision == revision && accepting) pcm.offer(samples, receivedAt);
    }

    private void fail(long revision, String reason) {
        synchronized (this) {
            if (this.revision != revision || !accepting) return;
            accepting = false;
            queue.clear();
        }
        failure.onFailure(revision, reason);
    }

    synchronized void shutdown() {
        closed = true;
        queue.clear();
        notifyAll();
    }

    private static final class Frame {
        final byte[] packet;
        final long receivedAt;

        Frame(byte[] packet, long receivedAt) {
            this.packet = packet;
            this.receivedAt = receivedAt;
        }
    }
}
