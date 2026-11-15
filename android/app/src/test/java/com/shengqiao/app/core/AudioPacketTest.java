package com.shengqiao.app.core;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioPacketTest {
    @Test
    public void microphonePacketMatchesProtocol() {
        short[] samples = {0, 1, -1, 32767, -32768};
        byte[] packet = AudioPacket.microphone(samples, samples.length, 0x12345678, 1.0f);
        assertEquals(2, packet[0]);
        assertEquals(0x12345678, AudioPacket.sequence(packet));
        for (int i = 0; i < samples.length; i++) {
            assertEquals(samples[i], AudioPacket.pcm16(packet, 8 + i * 2));
        }
        assertFalse(AudioPacket.isComputerAudio(packet));
    }

    @Test
    public void microphoneGainClipsInsteadOfWrapping() {
        byte[] packet = AudioPacket.microphone(new short[]{20000, -20000}, 2, 7, 2.0f);
        assertEquals(32767, AudioPacket.pcm16(packet, 8));
        assertEquals(-32768, AudioPacket.pcm16(packet, 10));
    }

    @Test
    public void computerAudioMustContainStereoFrames() {
        assertTrue(AudioPacket.isComputerAudio(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertFalse(AudioPacket.isComputerAudio(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    public void opusAudioUsesVersionThree() {
        byte[] packet = {3, 0, 0, 0, 9, 0, 0, 0, 20, 21, 22};
        assertTrue(AudioPacket.isComputerAudio(packet));
        assertTrue(AudioPacket.isOpus(packet));
        assertFalse(AudioPacket.isPcm(packet));
        assertEquals(9, AudioPacket.sequence(packet));
        assertFalse(AudioPacket.isComputerAudio(new byte[]{3, 0, 0, 0, 0, 0, 0, 0}));
    }
}
