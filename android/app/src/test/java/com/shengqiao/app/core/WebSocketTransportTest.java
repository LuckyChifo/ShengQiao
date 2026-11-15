package com.shengqiao.app.core;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebSocketTransportTest {
    @Test(timeout = 10000)
    public void pingBetweenFragmentsPreservesText() throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            WebSocketTransport client = new WebSocketTransport("127.0.0.1", server.getLocalPort(), "test",
                    new WebSocketTransport.Listener() {
                        public void onOpen() { }
                        public void onText(String value) { text.set(value); received.countDown(); }
                        public void onBinary(byte[] data) { }
                        public void onClose(String reason, boolean badKey) { }
                    });
            client.start();
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                String nonce = headerValue(header(new DataInputStream(peer.getInputStream())), "sec-websocket-key");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                peer.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                peer.getOutputStream().write(new byte[]{1, 2, 'h', 'e', (byte) 0x89, 1, 'x',
                        (byte) 0x80, 3, 'l', 'l', 'o'});
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertEquals("hello", text.get());
            } finally { client.close(); }
        }
    }

    @Test(timeout = 10000)
    public void connectsReceivesAudioAndMasksClientFrames() throws Exception {
        ServerSocket server = new ServerSocket(0);
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<byte[]> binary = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);
        Thread peer = new Thread(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                String header = header(input);
                String nonce = headerValue(header, "sec-websocket-key");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                        + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                byte[] frame = readFrame(input);
                received.set(new String(frame, StandardCharsets.UTF_8));
                output.write(new byte[]{(byte) 0x81, 7});
                output.write("\"hello\"".getBytes(StandardCharsets.UTF_8));
                byte[] audio = {1, 0, 0, 0, 9, 0, 0, 0, 1, 0, 2, 0};
                output.write(new byte[]{(byte) 0x82, (byte) audio.length});
                output.write(audio);
                output.flush();
                serverDone.countDown();
            } catch (Exception e) {
                received.set(e.toString());
                serverDone.countDown();
            }
        });
        peer.start();
        CountDownLatch audioReceived = new CountDownLatch(1);
        AtomicReference<WebSocketTransport> clientReference = new AtomicReference<>();
        WebSocketTransport client = new WebSocketTransport("127.0.0.1", server.getLocalPort(), "test",
                new WebSocketTransport.Listener() {
                    @Override
                    public void onOpen() {
                        try {
                            clientReference.get().sendText("{\"type\":\"config\",\"listen\":true}");
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override public void onText(String text) { }
                    @Override
                    public void onBinary(byte[] data) {
                        binary.set(data);
                        audioReceived.countDown();
                    }
                    @Override public void onClose(String reason, boolean badKey) { }
                });
        clientReference.set(client);
        client.start();
        assertTrue(serverDone.await(5, TimeUnit.SECONDS));
        assertTrue(audioReceived.await(5, TimeUnit.SECONDS));
        client.close();
        server.close();
        assertEquals("{\"type\":\"config\",\"listen\":true}", received.get());
        assertArrayEquals(new byte[]{1, 0, 0, 0, 9, 0, 0, 0, 1, 0, 2, 0}, binary.get());
    }

    private static String header(DataInputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!out.toString("US-ASCII").endsWith("\r\n\r\n")) {
            out.write(input.read());
        }
        return out.toString("US-ASCII");
    }

    private static String headerValue(String header, String name) {
        for (String line : header.split("\\r\\n")) {
            if (line.toLowerCase().startsWith(name + ":")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return "";
    }

    private static byte[] readFrame(DataInputStream input) throws Exception {
        int first = input.readUnsignedByte();
        int second = input.readUnsignedByte();
        int length = second & 0x7f;
        assertTrue((second & 0x80) != 0);
        if (length == 126) length = input.readUnsignedShort();
        byte[] mask = (second & 0x80) == 0 ? new byte[4] : readFully(input, 4);
        byte[] payload = readFully(input, length);
        for (int i = 0; i < length; i++) payload[i] ^= mask[i & 3];
        assertEquals(0x81, first);
        return payload;
    }

    private static byte[] readFully(DataInputStream input, int count) throws Exception {
        byte[] data = new byte[count];
        input.readFully(data);
        return data;
    }
}
