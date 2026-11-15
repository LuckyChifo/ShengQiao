package com.shengqiao.app.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebSocketTransport implements Closeable {
    public interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        void onClose(String reason, boolean badKey);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final String host;
    private final int port;
    private final String key;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object writeLock = new Object();
    private volatile boolean open;
    private volatile boolean closedCallback;
    private volatile Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private Thread thread;

    public WebSocketTransport(String host, int port, String key, Listener listener) {
        this.host = host;
        this.port = port;
        this.key = key;
        this.listener = listener;
    }

    public void start() {
        thread = new Thread(this::run, "shengqiao-websocket");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public boolean isOpen() {
        return open && running.get();
    }

    public void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendBinary(byte[] data) throws IOException {
        sendFrame(0x2, data);
    }

    private void run() {
        if (!running.get()) {
            return;
        }
        String reason = "连接断开了";
        boolean badKey = false;
        try {
            socket = new Socket();
            if (!running.get()) return;
            socket.connect(new InetSocketAddress(host, port), 6000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(1000);
            socket.setSendBufferSize(64 * 1024);
            socket.setReceiveBufferSize(64 * 1024);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            handshake();
            if (!running.get()) return;
            open = true;
            listener.onOpen();
            readLoop();
            reason = "电脑关闭了连接";
        } catch (BadKeyException e) {
            badKey = true;
            reason = "访问码不正确";
        } catch (SocketTimeoutException e) {
            reason = "连接电脑超时";
        } catch (Exception e) {
            if (running.get()) {
                reason = e.getMessage() == null ? "网络连接失败" : e.getMessage();
            }
        } finally {
            open = false;
            closeSocket();
            if (running.compareAndSet(true, false) && !closedCallback) {
                closedCallback = true;
                listener.onClose(reason, badKey);
            }
        }
    }

    private void handshake() throws Exception {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String websocketKey = Base64.getEncoder().encodeToString(nonce);
        String path = "/ws?k=" + URLEncoder.encode(key, "UTF-8");
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + websocketKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "X-SQ-Key: " + key + "\r\n"
                + "User-Agent: ShengQiao/2.0 (Android)\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String header = readHeader();
        String[] lines = header.split("\\r\\n");
        if (lines.length == 0 || !lines[0].contains(" 101 ")) {
            throw new IOException("电脑没有接受 WebSocket 连接");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }
        String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((websocketKey + GUID).getBytes(StandardCharsets.US_ASCII)));
        if (!expected.equals(headers.get("sec-websocket-accept"))) {
            throw new IOException("WebSocket 握手校验失败");
        }
    }

    private String readHeader() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16384) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("电脑在握手时断开了连接");
            }
            bytes.write(value);
            int expected = matched % 2 == 0 ? '\r' : '\n';
            matched = value == expected ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == 4) {
                return bytes.toString("US-ASCII");
            }
        }
        throw new IOException("WebSocket 响应头过长");
    }

    private void readLoop() throws Exception {
        int messageOpcode = 0;
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (running.get()) {
            int b0;
            try {
                b0 = input.read();
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (b0 < 0) {
                throw new EOFException("电脑断开了连接");
            }
            int b1 = readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            long length = b1 & 0x7f;
            if (length == 126) {
                length = readUnsignedShort();
            } else if (length == 127) {
                length = input.readLong();
            }
            if (length < 0 || length > 262144) {
                throw new IOException("收到异常大小的数据帧");
            }
            if ((b0 & 0x70) != 0 || (opcode != 0 && opcode != 1 && opcode != 2
                    && opcode != 8 && opcode != 9 && opcode != 10)
                    || (opcode >= 8 && (!fin || length > 125))) {
                throw new IOException("收到无效的 WebSocket 帧");
            }
            if (opcode < 8 && message.size() + length > 262144) {
                throw new IOException("收到异常大小的分片消息");
            }
            byte[] mask = (b1 & 0x80) == 0 ? null : readBytes(4);
            byte[] payload = readBytes((int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            if (opcode == 0x8) {
                badKeyFrom(payload);
                return;
            }
            if (opcode == 0x9) {
                sendFrame(0xA, payload);
                continue;
            }
            if (opcode == 0xA) {
                continue;
            }
            if (opcode != 0) {
                if (messageOpcode != 0) throw new IOException("分片消息尚未结束");
                messageOpcode = opcode;
                message.reset();
            } else if (messageOpcode == 0) {
                throw new IOException("收到孤立的续帧");
            }
            message.write(payload);
            if (fin) {
                byte[] data = message.toByteArray();
                if (messageOpcode == 0x1) {
                    listener.onText(new String(data, StandardCharsets.UTF_8));
                } else if (messageOpcode == 0x2) {
                    listener.onBinary(data);
                }
                message.reset();
                messageOpcode = 0;
            }
        }
    }

    private void badKeyFrom(byte[] payload) throws BadKeyException {
        if (payload.length >= 2 && ((payload[0] & 0xff) << 8 | payload[1] & 0xff) == 4001) {
            throw new BadKeyException();
        }
    }

    private int readUnsignedByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("连接中断");
        }
        return value;
    }

    private int readUnsignedShort() throws IOException {
        return readUnsignedByte() << 8 | readUnsignedByte();
    }

    private byte[] readBytes(int count) throws IOException {
        byte[] data = new byte[count];
        input.readFully(data);
        return data;
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        if (!running.get()) {
            return;
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        int headerLength = payload.length < 126 ? 6 : (payload.length < 65536 ? 8 : 14);
        byte[] frame = new byte[headerLength + payload.length];
        frame[0] = (byte) (0x80 | opcode);
        int maskOffset;
        if (payload.length < 126) {
            frame[1] = (byte) (0x80 | payload.length);
            maskOffset = 2;
        } else if (payload.length < 65536) {
            frame[1] = (byte) (0x80 | 126);
            frame[2] = (byte) (payload.length >>> 8);
            frame[3] = (byte) payload.length;
            maskOffset = 4;
        } else {
            frame[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) ((long) payload.length >>> (56 - i * 8));
            }
            maskOffset = 10;
        }
        System.arraycopy(mask, 0, frame, maskOffset, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[headerLength + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        synchronized (writeLock) {
            if (output == null) {
                return;
            }
            output.write(frame);
            output.flush();
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            open = false;
            closeSocket();
            if (!closedCallback) {
                closedCallback = true;
                listener.onClose("已断开", false);
            }
        }
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class BadKeyException extends IOException {
    }
}
