package com.shengqiao.app.core;

/** Bounds repeated zero-progress nonblocking audio I/O without counting idle time between blocks. */
public final class AudioIoProgress {
    private static final long TIMEOUT_NS = 250_000_000L;
    private long lastProgress;

    public AudioIoProgress(long now) {
        lastProgress = now;
    }

    public void record(int count, long now) {
        if (count < 0) throw new IllegalStateException("音频读写错误：" + count);
        if (count > 0) lastProgress = now;
        else if (now - lastProgress >= TIMEOUT_NS) {
            throw new IllegalStateException("音频读写无进展");
        }
    }
}
