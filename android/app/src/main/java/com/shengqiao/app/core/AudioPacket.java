package com.shengqiao.app.core;

public final class AudioPacket {
    private AudioPacket() {
    }

    public static int sequence(byte[] packet) {
        if (packet == null || packet.length < 8) {
            return -1;
        }
        return (packet[4] & 0xff)
                | ((packet[5] & 0xff) << 8)
                | ((packet[6] & 0xff) << 16)
                | ((packet[7] & 0xff) << 24);
    }

    public static boolean isComputerAudio(byte[] packet) {
        return isPcm(packet) || isOpus(packet);
    }

    public static boolean isPcm(byte[] packet) {
        return packet != null && packet.length >= 10 && packet[0] == 1
                && (packet.length - 8) % 4 == 0;
    }

    public static boolean isOpus(byte[] packet) {
        return packet != null && packet.length > 8 && packet[0] == 3;
    }

    public static byte[] microphone(short[] samples, int count, int sequence, float gain) {
        byte[] packet = new byte[8 + count * 2];
        packet[0] = 2;
        packet[4] = (byte) sequence;
        packet[5] = (byte) (sequence >>> 8);
        packet[6] = (byte) (sequence >>> 16);
        packet[7] = (byte) (sequence >>> 24);
        for (int i = 0; i < count; i++) {
            int value = Math.round(samples[i] * gain);
            value = Math.max(-32768, Math.min(32767, value));
            packet[8 + i * 2] = (byte) value;
            packet[9 + i * 2] = (byte) (value >>> 8);
        }
        return packet;
    }

    public static short pcm16(byte[] data, int offset) {
        return (short) ((data[offset] & 0xff) | (data[offset + 1] << 8));
    }
}
