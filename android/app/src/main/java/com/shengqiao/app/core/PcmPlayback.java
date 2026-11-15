package com.shengqiao.app.core;

/** Stateful 10 ms stereo rendering, including a single fade when the stream stalls. */
public final class PcmPlayback {
    private short previousLeft;
    private short previousRight;
    private boolean fadeIn = true;

    public void render(byte[] packet, float gain, short[] output) {
        int frames = output.length / 2;
        if (packet == null) {
            for (int i = 0; i < frames; i++) {
                float factor = i < 96 ? (96 - i) / 96.0f : 0;
                output[i * 2] = (short) (previousLeft * factor);
                output[i * 2 + 1] = (short) (previousRight * factor);
            }
            fadeIn = true;
        } else {
            int available = Math.min(frames, (packet.length - 8) / 4);
            for (int i = 0; i < frames; i++) {
                float envelope = fadeIn && i < 96 ? i / 96.0f : 1;
                for (int channel = 0; channel < 2; channel++) {
                    int sample = i < available ? Math.round(AudioPacket.pcm16(packet,
                            8 + i * 4 + channel * 2) * gain * envelope) : 0;
                    output[i * 2 + channel] = (short) Math.max(-32768, Math.min(32767, sample));
                }
            }
            fadeIn = false;
        }
        // Save the actual output tail, including silence, so a fade never repeats.
        previousLeft = output[output.length - 2];
        previousRight = output[output.length - 1];
    }
}
