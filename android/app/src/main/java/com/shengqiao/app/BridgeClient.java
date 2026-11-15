package com.shengqiao.app;

import com.shengqiao.app.core.AudioPacket;
import com.shengqiao.app.core.StreamConfig;
import com.shengqiao.app.core.WebSocketTransport;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class BridgeClient {
    interface Listener {
        void onOpen();
        void onHello(JSONObject message);
        void onMessage(JSONObject message);
        void onAudio(byte[] packet);
        void onClosed(String reason, boolean badKey);
        default void onStream(StreamConfig stream) {}
        default void onStreamReset() {}
        default void onLegacyStream() {}
    }

    private final Listener listener;
    private final String deviceName;
    private final AtomicLong lastMessageAt = new AtomicLong(System.nanoTime());
    private final ArrayBlockingQueue<Outgoing> microphoneQueue = new ArrayBlockingQueue<>(30);
    private final ArrayBlockingQueue<Outgoing> controlQueue = new ArrayBlockingQueue<>(64);
    private final Semaphore pending = new Semaphore(0);
    private final Executor callbacks;
    private volatile long generation;
    private final Thread microphoneSender;
    private volatile WebSocketTransport transport;
    private boolean listen;
    private boolean mic;
    private int bitrate;
    private int sequence;
    private boolean streamProtocol;
    private boolean configured;
    private String mode = "standard";
    private int configId;
    private int audioConfigId;
    private StreamConfig stream;
    private int lastStreamId;
    private int receivedNext;
    private boolean ackDirty;
    private long lastAckAt;

    BridgeClient(Listener listener, Executor callbacks, String deviceName) {
        this.listener = listener;
        this.callbacks = callbacks;
        this.deviceName = deviceName;
        microphoneSender = new Thread(this::sendMicrophoneFrames, "shengqiao-mic-network");
        microphoneSender.start();
    }

    synchronized void connect(String host, int port, String key) {
        closeTransport();
        final long session = generation;
        WebSocketTransport next = new WebSocketTransport(host, port, key,
                new WebSocketTransport.Listener() {
                    @Override
                    public void onOpen() {
                        lastMessageAt.set(System.nanoTime());
                        dispatch(session, listener::onOpen);
                    }

                    @Override
                    public void onText(String text) {
                        lastMessageAt.set(System.nanoTime());
                        try {
                            JSONObject message = new JSONObject(text);
                            String type = message.optString("type");
                            if ("hello".equals(type)) {
                                synchronized (BridgeClient.this) {
                                    if (session != generation || configured) return;
                                    streamProtocol = StreamConfig.supported(message);
                                }
                                dispatch(session, () -> listener.onHello(message));
                            } else if ("stream_info".equals(type)) {
                                receiveStream(session, message);
                            } else {
                                dispatch(session, () -> listener.onMessage(message));
                            }
                        } catch (JSONException ignored) {
                        }
                    }

                    @Override
                    public void onBinary(byte[] data) {
                        lastMessageAt.set(System.nanoTime());
                        receiveAudio(session, data);
                    }

                    @Override
                    public void onClose(String reason, boolean badKey) {
                        dispatch(session, () -> listener.onClosed(reason, badKey));
                    }
                });
        transport = next;
        next.start();
    }

    synchronized void setAudioState(boolean listen, boolean mic, int bitrate) {
        setAudioState(listen, mic, bitrate, "standard");
    }

    synchronized int setAudioState(boolean listen, boolean mic, int bitrate, String mode) {
        if (transport == null || !transport.isOpen()) return configId;
        boolean first = !configured;
        boolean listenChanged = first || this.listen != listen;
        boolean micChanged = first || this.mic != mic;
        boolean bitrateChanged = first || this.bitrate != bitrate;
        boolean modeChanged = streamProtocol && (first || !mode.equals(this.mode));
        if (!listenChanged && !micChanged && !bitrateChanged && !modeChanged) return configId;
        JSONObject message = new JSONObject();
        try {
            message.put("type", "config");
            if (listenChanged) message.put("listen", listen);
            if (micChanged) message.put("mic", mic);
            if (bitrateChanged) message.put("bitrate", bitrate);
            if (modeChanged) message.put("latencyMode", mode);
            if (first) {
                message.put("micRate", 48000);
                message.put("deviceName", deviceName);
                if (streamProtocol) {
                    message.put("clientFeatures", new JSONArray()
                            .put(StreamConfig.LATENCY_FEATURE).put(StreamConfig.STREAM_FEATURE));
                }
            }
            if (streamProtocol) message.put("configId", ++configId);
            if (listenChanged || bitrateChanged || modeChanged) {
                audioConfigId = configId;
                clearStream();
                listener.onStreamReset();
                if (!streamProtocol && listen) listener.onLegacyStream();
            }
            this.listen = listen;
            this.mic = mic;
            this.bitrate = bitrate;
            this.mode = mode;
            configured = true;
            if (!mic) microphoneQueue.clear();
            sendJson(message);
        } catch (JSONException ignored) {
        }
        return configId;
    }

    synchronized int setDenoise(String mode) {
        if (transport == null || !transport.isOpen() || (streamProtocol && !configured)) return configId;
        JSONObject message = new JSONObject();
        try {
            message.put("type", "config");
            message.put("denoise", mode);
            if (streamProtocol) message.put("configId", ++configId);
            sendJson(message);
        } catch (JSONException ignored) {
        }
        return configId;
    }

    synchronized int configId() {
        return configId;
    }

    private synchronized void receiveStream(long session, JSONObject message) {
        if (session != generation || !streamProtocol || !configured || !listen) return;
        StreamConfig next = StreamConfig.parse(message);
        if (next == null || next.id <= lastStreamId
                || next.configId < audioConfigId || next.configId > configId) return;
        clearStream();
        stream = next;
        lastStreamId = next.id;
        listener.onStream(next);
    }

    private synchronized void receiveAudio(long session, byte[] packet) {
        if (session != generation || !configured || !listen) return;
        if (!streamProtocol) {
            if (AudioPacket.isComputerAudio(packet)) listener.onAudio(packet);
            return;
        }
        if (stream == null || !stream.accepts(packet)) return;
        listener.onAudio(packet);
        if (!stream.low || receivedNext != AudioPacket.sequence(packet)) return;
        receivedNext++;
        ackDirty = true;
        wakeSender();
    }

    private synchronized Outgoing takeAck() {
        if (!ackDirty || stream == null || transport == null || !transport.isOpen()) return null;
        long now = System.nanoTime();
        if (lastAckAt != 0 && now - lastAckAt < 100_000_000L) return null;
        ackDirty = false;
        lastAckAt = now;
        try {
            JSONObject ack = new JSONObject().put("type", "audio_ack")
                    .put("streamId", stream.id).put("receivedSequence", Integer.toUnsignedLong(receivedNext - 1));
            return new Outgoing(transport, ack.toString(), null);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private synchronized long ackWaitNanos() {
        if (!ackDirty) return -1;
        return Math.max(0, 100_000_000L - (System.nanoTime() - lastAckAt));
    }

    private void clearStream() {
        stream = null;
        receivedNext = 0;
        lastAckAt = 0;
        ackDirty = false;
    }

    void ping(double time) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "ping");
            message.put("t", time);
            sendJson(message);
        } catch (JSONException ignored) {
        }
    }

    void stats(int buffer, int target, int underruns, int rtt) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "stats");
            message.put("buf", buffer);
            message.put("tgt", target);
            message.put("und", underruns);
            message.put("rtt", rtt);
            sendJson(message);
        } catch (JSONException ignored) {
        }
    }

    private synchronized void sendJson(JSONObject message) {
        if (transport == null || !transport.isOpen()) {
            return;
        }
        if (!controlQueue.offer(new Outgoing(transport, message.toString(), null))) {
            transport.close();
        }
        wakeSender();
    }

    synchronized void sendMicrophone(short[] samples, int count, float gain) {
        if (!mic || transport == null || !transport.isOpen()) {
            return;
        }
        Outgoing packet = new Outgoing(transport, null,
                AudioPacket.microphone(samples, count, sequence++, gain));
        if (!microphoneQueue.offer(packet)) {
            microphoneQueue.poll();
            microphoneQueue.offer(packet);
        }
        wakeSender();
    }

    private void sendMicrophoneFrames() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long ackWait = ackWaitNanos();
                if (ackWait < 0) pending.acquire();
                else pending.tryAcquire(ackWait, TimeUnit.NANOSECONDS);
                pending.drainPermits();
                Outgoing packet;
                while ((packet = controlQueue.poll()) != null
                        || (packet = takeAck()) != null
                        || (packet = microphoneQueue.poll()) != null) {
                    WebSocketTransport current = packet.transport;
                    if (current != transport || !current.isOpen()) continue;
                    try {
                        if (packet.text != null) current.sendText(packet.text);
                        else current.sendBinary(packet.audio);
                    } catch (IOException e) {
                        current.close();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isOpen() {
        WebSocketTransport current;
        synchronized (this) {
            current = transport;
        }
        return current != null && current.isOpen();
    }

    long millisecondsSinceMessage() {
        return (System.nanoTime() - lastMessageAt.get()) / 1_000_000L;
    }

    synchronized void close() {
        controlQueue.clear();
        microphoneQueue.clear();
        clearStream();
        if (transport != null) transport.close();
    }

    synchronized void disconnect() {
        closeTransport();
    }

    synchronized void shutdown() {
        closeTransport();
        microphoneSender.interrupt();
    }

    private void closeTransport() {
        generation++;
        configured = false;
        streamProtocol = false;
        configId = 0;
        audioConfigId = 0;
        lastStreamId = 0;
        sequence = 0;
        clearStream();
        listener.onStreamReset();
        controlQueue.clear();
        microphoneQueue.clear();
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    private void dispatch(long session, Runnable callback) {
        callbacks.execute(() -> {
            if (session == generation) callback.run();
        });
    }

    private void wakeSender() {
        if (pending.availablePermits() == 0) pending.release();
    }

    private static final class Outgoing {
        final WebSocketTransport transport;
        final String text;
        final byte[] audio;

        Outgoing(WebSocketTransport transport, String text, byte[] audio) {
            this.transport = transport;
            this.text = text;
            this.audio = audio;
        }
    }
}
