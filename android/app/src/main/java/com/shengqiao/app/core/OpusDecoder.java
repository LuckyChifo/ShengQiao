package com.shengqiao.app.core;

import java.io.Closeable;

public final class OpusDecoder implements Closeable {
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAMES = 480;
    private static final int HEADER_SIZE = 8;

    static {
        System.loadLibrary("shengqiao_opus");
    }

    private final byte[] pcm = new byte[HEADER_SIZE + FRAMES * CHANNELS * 2];
    private long decoder;

    public OpusDecoder() {
        decoder = nativeCreate(SAMPLE_RATE, CHANNELS);
        if (decoder == 0) {
            throw new IllegalStateException("内置 Opus 解码器初始化失败");
        }
    }

    public synchronized byte[] decode(byte[] packet) {
        if (!AudioPacket.isOpus(packet)) {
            throw new IllegalArgumentException("不是 Opus 音频包");
        }
        return decodeFrame(packet, HEADER_SIZE, packet.length - HEADER_SIZE);
    }

    public synchronized byte[] concealLoss() {
        return decodeFrame(null, 0, 0);
    }

    private byte[] decodeFrame(byte[] packet, int offset, int length) {
        if (decoder == 0) {
            throw new IllegalStateException("Opus 解码器已关闭");
        }
        int samples = nativeDecode(decoder, packet, offset, length, pcm, HEADER_SIZE);
        if (samples < 0) {
            throw new IllegalStateException("Opus 解码失败，错误码：" + samples);
        }
        if (samples != FRAMES) {
            throw new IllegalStateException("Opus 音频帧长度错误：" + samples);
        }
        return pcm;
    }

    static String libraryVersion() {
        return nativeVersion();
    }

    @Override
    public synchronized void close() {
        if (decoder == 0) {
            return;
        }
        nativeDestroy(decoder);
        decoder = 0;
    }

    private static native long nativeCreate(int sampleRate, int channels);

    private static native int nativeDecode(long decoder, byte[] packet, int offset, int length,
                                           byte[] output, int outputOffset);

    private static native void nativeDestroy(long decoder);

    private static native String nativeVersion();
}
