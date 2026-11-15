package com.shengqiao.app.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmPlaybackTest {
    @Test
    public void prolongedStallStaysSilentAndRecoveryFadesIn() {
        PcmPlayback playback = new PcmPlayback();
        byte[] packet = new byte[1928];
        packet[0] = 1;
        for (int i = 8; i < packet.length; i += 2) {
            packet[i] = 0x10;
            packet[i + 1] = 0x27; // 10000, nonzero tail makes repeated fades observable.
        }
        short[] output = new short[960];
        playback.render(packet, 1, output);
        assertEquals(10000, output[959]);
        playback.render(null, 1, output);
        assertEquals(10000, output[0]);
        assertEquals(0, output[959]);
        for (int i = 0; i < 100; i++) {
            playback.render(null, 1, output);
            assertArrayEquals(new short[960], output);
        }
        playback.render(packet, 1, output);
        assertEquals(0, output[0]);
        assertEquals(10000, output[959]);
    }
}
