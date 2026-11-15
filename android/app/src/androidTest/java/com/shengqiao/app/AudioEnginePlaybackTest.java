package com.shengqiao.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.shengqiao.app.core.OpusDecoderTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class AudioEnginePlaybackTest {
    @Test
    public void opusPacketsDecodeAndPlayThroughAudioTrack() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        AudioEngine engine = new AudioEngine(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new AudioEngine.Listener() {
                    @Override
                    public void onAudioError(String message) {
                        error.compareAndSet(null, message);
                    }

                    @Override
                    public void onMicrophoneError(long revision, String message) {
                        error.compareAndSet(null, message);
                    }
                });
        try {
            engine.setTargetMs(30);
            engine.setListening(true);
            for (byte[] packet : OpusDecoderTest.encodedPackets(128)) {
                engine.offer(packet);
                Thread.sleep(12);
            }
            Thread.sleep(300);
            assertNull(error.get());
        } finally {
            engine.shutdown();
        }
    }
}
