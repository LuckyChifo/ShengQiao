package com.shengqiao.app.core;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class ProtocolIntegrationTest {
    @Test(timeout = 20000)
    public void realComputerSendsAudioAndAcceptsAndroidMicrophone() throws Exception {
        String host = System.getenv("SQ_TEST_HOST");
        String key = System.getenv("SQ_TEST_KEY");
        assumeTrue(host != null && key != null);
        int port = Integer.parseInt(System.getenv().getOrDefault("SQ_TEST_PORT", "23333"));
        CountDownLatch hello = new CountDownLatch(1);
        CountDownLatch audio = new CountDownLatch(20);
        CountDownLatch opusAudio = new CountDownLatch(20);
        CountDownLatch opusStatus = new CountDownLatch(1);
        CountDownLatch microphone = new CountDownLatch(1);
        AtomicReference<WebSocketTransport> reference = new AtomicReference<>();
        WebSocketTransport client = new WebSocketTransport(host, port, key,
                new WebSocketTransport.Listener() {
                    @Override
                    public void onOpen() {
                        try {
                            reference.get().sendText("{\"type\":\"config\",\"listen\":true,\"mic\":false,\"deviceName\":\"Android 测试机\"}");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void onText(String text) {
                        if (text.contains("\"type\"") && text.contains("\"hello\"")) {
                            hello.countDown();
                        }
                        if (text.contains("\"bitrate\": 128")) {
                            opusStatus.countDown();
                        }
                        if (text.contains("\"state\"") && text.contains("\"running\"")) {
                            microphone.countDown();
                        }
                    }

                    @Override
                    public void onBinary(byte[] data) {
                        if (AudioPacket.isPcm(data) && data.length == 1928) {
                            audio.countDown();
                        }
                        if (AudioPacket.isOpus(data)) {
                            opusAudio.countDown();
                        }
                    }

                    @Override
                    public void onClose(String reason, boolean badKey) {
                        assertFalse(badKey);
                    }
                });
        reference.set(client);
        client.start();
        assertTrue("没有收到电脑 hello", hello.await(5, TimeUnit.SECONDS));
        assertTrue("没有收到足够的电脑音频帧", audio.await(5, TimeUnit.SECONDS));
        client.sendText("{\"type\":\"config\",\"bitrate\":128}");
        assertTrue("没有收到 Opus 音频帧", opusAudio.await(5, TimeUnit.SECONDS));
        assertTrue("电脑没有确认 128 kbps 已生效", opusStatus.await(3, TimeUnit.SECONDS));
        client.sendText("{\"type\":\"config\",\"listen\":true,\"mic\":true,\"micRate\":48000,\"denoise\":\"off\"}");
        short[] samples = new short[480];
        for (int sequence = 0; sequence < 150; sequence++) {
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) Math.round(Math.sin((sequence * 480 + i) * 2 * Math.PI * 1000 / 48000) * 12000);
            }
            client.sendBinary(AudioPacket.microphone(samples, samples.length, sequence, 1.0f));
            Thread.sleep(10);
        }
        assertTrue("电脑没有报告 Android 麦克风正在运行", microphone.await(3, TimeUnit.SECONDS));
        client.sendText("{\"type\":\"config\",\"listen\":false,\"mic\":false}");
        client.close();
    }
}
