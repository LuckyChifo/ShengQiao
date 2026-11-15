package com.shengqiao.app;

import com.shengqiao.app.core.StreamConfig;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class BridgeClientTest {
    private static final class Listener implements BridgeClient.Listener {
        final AtomicInteger calls = new AtomicInteger();
        public void onOpen() { calls.incrementAndGet(); }
        public void onHello(JSONObject message) { calls.incrementAndGet(); }
        public void onMessage(JSONObject message) { calls.incrementAndGet(); }
        public void onAudio(byte[] packet) { calls.incrementAndGet(); }
        public void onClosed(String reason, boolean badKey) { calls.incrementAndGet(); }
    }

    private static void handshake(Socket socket) throws Exception {
        socket.setSoTimeout(3000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (!bytes.toString("US-ASCII").endsWith("\r\n\r\n")) {
            int b = socket.getInputStream().read();
            if (b < 0) throw new IllegalStateException("EOF during handshake");
            bytes.write(b);
        }
        String nonce = "";
        for (String line : bytes.toString("US-ASCII").split("\r\n")) {
            if (line.startsWith("Sec-WebSocket-Key:")) nonce = line.split(":", 2)[1].trim();
        }
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    @Test(timeout = 10000)
    public void disconnectDiscardsAlreadyQueuedCallbacks() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        Listener listener = new Listener();
        BridgeClient client = new BridgeClient(listener, callbacks::add, "测试手机");
        try (ServerSocket server = new ServerSocket(0)) {
            client.connect("127.0.0.1", server.getLocalPort(), "test");
            server.setSoTimeout(3000);
            try (Socket peer = server.accept()) {
                handshake(peer);
                Runnable open = callbacks.poll(3, TimeUnit.SECONDS);
                assertNotNull(open);
                client.disconnect();
                open.run();
                assertEquals(0, listener.calls.get());
            }
        } finally { client.shutdown(); }
    }

    @Test(timeout = 10000)
    public void configContainsSelectedBitrateAndDeviceName() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        BridgeClient client = new BridgeClient(new Listener(), callbacks::add, "测试手机");
        try (ServerSocket server = new ServerSocket(0)) {
            client.connect("127.0.0.1", server.getLocalPort(), "test");
            server.setSoTimeout(3000);
            try (Socket peer = server.accept()) {
                handshake(peer);
                assertNotNull(callbacks.poll(3, TimeUnit.SECONDS));
                client.setAudioState(true, false, 320);
                JSONObject config = new JSONObject(readTextFrame(peer));
                assertEquals("config", config.getString("type"));
                assertEquals(320, config.getInt("bitrate"));
                assertEquals("测试手机", config.getString("deviceName"));
                assertTrue(config.getBoolean("listen"));
                assertFalse(config.getBoolean("mic"));
            }
        } finally { client.shutdown(); }
    }

    private static String readTextFrame(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int first = input.readUnsignedByte();
        int second = input.readUnsignedByte();
        assertEquals(1, first & 0x0f);
        assertTrue((second & 0x80) != 0);
        long length = second & 0x7f;
        if (length == 126) length = input.readUnsignedShort();
        byte[] mask = new byte[4];
        input.readFully(mask);
        byte[] payload = new byte[(int) length];
        input.readFully(payload);
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Test(timeout = 10000)
    public void streamBoundaryPrecedesAudioAndModeChangeDoesNotResendMic() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        BridgeClient[] connection = new BridgeClient[1];
        BridgeClient client = new BridgeClient(new BridgeClient.Listener() {
            public void onOpen() {}
            public void onHello(JSONObject message) { connection[0].setAudioState(true, true, 0, "low"); }
            public void onMessage(JSONObject message) {}
            public void onAudio(byte[] packet) { received.add("audio"); }
            public void onClosed(String reason, boolean badKey) {}
            public void onStream(StreamConfig stream) { received.add("stream" + stream.id); }
        }, callbacks::add, "测试手机");
        connection[0] = client;
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            client.connect("127.0.0.1", server.getLocalPort(), "test");
            try (Socket peer = server.accept()) {
                handshake(peer);
                callbacks.poll(3, TimeUnit.SECONDS).run();
                sendText(peer, "{\"type\":\"hello\",\"features\":[\"latency_profile_v1\",\"stream_info_v1\"],"
                        + "\"latencyProfileVersion\":1}");
                callbacks.poll(3, TimeUnit.SECONDS).run();
                JSONObject initial = new JSONObject(readTextFrame(peer));
                assertEquals(1, initial.getInt("configId"));
                assertTrue(initial.getBoolean("mic"));
                byte[] pcm = new byte[1928];
                pcm[0] = 1;
                sendFrame(peer, 2, pcm);
                sendText(peer, streamInfo(1, 1, "low"));
                sendFrame(peer, 2, pcm);
                pcm[4] = 1;
                sendFrame(peer, 2, pcm);
                assertEquals("stream1", received.poll(3, TimeUnit.SECONDS));
                assertEquals("audio", received.poll(3, TimeUnit.SECONDS));
                assertEquals("audio", received.poll(3, TimeUnit.SECONDS));
                JSONObject ack = new JSONObject(readTextFrame(peer));
                assertEquals("audio_ack", ack.getString("type"));
                assertEquals(1, ack.getInt("streamId"));
                if (ack.getInt("receivedSequence") == 0) {
                    assertEquals(1, new JSONObject(readTextFrame(peer)).getInt("receivedSequence"));
                }
                for (int i = 0; i < 100; i++) {
                    String mode = i % 2 == 0 ? "standard" : "low";
                    client.setAudioState(true, true, 0, mode);
                    JSONObject change = new JSONObject(readTextFrame(peer));
                    assertEquals(i + 2, change.getInt("configId"));
                    assertEquals(mode, change.getString("latencyMode"));
                    assertFalse(change.has("mic"));
                }
                assertEquals(101, client.setAudioState(true, true, 0, "low"));
                pcm[4] = 0;
                sendText(peer, streamInfo(2, 100, "standard"));
                sendFrame(peer, 2, pcm);
                sendText(peer, streamInfo(3, 101, "low"));
                sendFrame(peer, 2, pcm);
                assertEquals("stream3", received.poll(3, TimeUnit.SECONDS));
                assertEquals("audio", received.poll(3, TimeUnit.SECONDS));
            }
        } finally {
            client.shutdown();
        }
    }

    private static String streamInfo(int id, int config, String mode) throws Exception {
        return new JSONObject().put("type", "stream_info").put("streamId", id).put("configId", config)
                .put("latencyMode", mode).put("codec", "pcm").put("bitrate", 0)
                .put("sampleRate", 48000).put("channels", 2).put("frameSamples", 480)
                .put("firstSequence", 0).put("codecDelaySamples", 0)
                .put("opusApplication", JSONObject.NULL).toString();
    }

    @Test(timeout = 20000)
    public void realServerNegotiatesModesAndReceivesAudioAcks() throws Exception {
        String host = System.getenv("SQ_TEST_HOST");
        String key = System.getenv("SQ_TEST_KEY");
        org.junit.Assume.assumeTrue(host != null && key != null);
        int port = Integer.parseInt(System.getenv().getOrDefault("SQ_TEST_PORT", "23333"));
        CountDownLatch hello = new CountDownLatch(1);
        BlockingQueue<StreamConfig> streams = new LinkedBlockingQueue<>();
        BlockingQueue<JSONObject> statuses = new LinkedBlockingQueue<>();
        AtomicReference<StreamConfig> current = new AtomicReference<>();
        CountDownLatch lowFrames = new CountDownLatch(120);
        CountDownLatch standardFrames = new CountDownLatch(10);
        BridgeClient client = new BridgeClient(new BridgeClient.Listener() {
            public void onOpen() {}
            public void onHello(JSONObject message) { if (StreamConfig.supported(message)) hello.countDown(); }
            public void onMessage(JSONObject message) {
                if ("status".equals(message.optString("type"))) statuses.add(message);
            }
            public void onStream(StreamConfig stream) { current.set(stream); streams.add(stream); }
            public void onStreamReset() { current.set(null); }
            public void onAudio(byte[] packet) {
                StreamConfig stream = current.get();
                if (stream == null) return;
                if (stream.low && stream.opus) lowFrames.countDown();
                if (!stream.low && !stream.opus) standardFrames.countDown();
            }
            public void onClosed(String reason, boolean badKey) {}
        }, Runnable::run, "Android low-latency integration");
        try {
            client.connect(host, port, key);
            assertTrue(hello.await(3, TimeUnit.SECONDS));
            assertEquals(1, client.setAudioState(true, false, 128, "low"));
            StreamConfig low = streams.poll(5, TimeUnit.SECONDS);
            assertNotNull(low);
            assertTrue(low.low && low.opus);
            assertEquals(120, low.delaySamples);
            assertTrue("low stream dropped before the ACK deadline", lowFrames.await(5, TimeUnit.SECONDS));
            assertEquals(2, client.setAudioState(true, false, 0, "standard"));
            StreamConfig standard;
            do {
                standard = streams.poll(3, TimeUnit.SECONDS);
                assertNotNull(standard);
            } while (standard.configId != 2);
            assertFalse(standard.low || standard.opus);
            assertTrue(standard.id > low.id);
            assertTrue(standardFrames.await(3, TimeUnit.SECONDS));
            assertTrue(statuses.stream().anyMatch(status -> status.optInt("configId") == 2
                    && "standard".equals(status.optString("latencyMode"))));
        } finally {
            client.shutdown();
        }
    }

    private static void sendText(Socket socket, String text) throws Exception {
        sendFrame(socket, 1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendFrame(Socket socket, int opcode, byte[] payload) throws Exception {
        java.io.DataOutputStream output = new java.io.DataOutputStream(socket.getOutputStream());
        output.writeByte(0x80 | opcode);
        if (payload.length < 126) {
            output.writeByte(payload.length);
        } else {
            output.writeByte(126);
            output.writeShort(payload.length);
        }
        output.write(payload);
        output.flush();
    }

    @Test(timeout = 10000)
    public void stalledPeerDoesNotBlockControlCallerOrDisconnect() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        BridgeClient client = new BridgeClient(new Listener(), callbacks::add, "测试手机");
        try (ServerSocket server = new ServerSocket(0)) {
            server.setReceiveBufferSize(1024);
            client.connect("127.0.0.1", server.getLocalPort(), "test");
            server.setSoTimeout(3000);
            try (Socket peer = server.accept()) {
                handshake(peer);
                assertNotNull(callbacks.poll(3, TimeUnit.SECONDS));
                CountDownLatch returned = new CountDownLatch(1);
                Thread caller = new Thread(() -> {
                    // The peer never reads: a synchronous write would fill its TCP window.
                    client.setDenoise("x".repeat(4 * 1024 * 1024));
                    returned.countDown();
                });
                caller.setDaemon(true);
                caller.start();
                assertTrue("control send blocked caller", returned.await(2, TimeUnit.SECONDS));
                client.disconnect();
                assertFalse(client.isOpen());
            }
        } finally { client.shutdown(); }
    }
}
