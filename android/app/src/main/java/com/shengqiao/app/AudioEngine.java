package com.shengqiao.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

import com.shengqiao.app.core.PcmJitterBuffer;
import com.shengqiao.app.core.AudioIoProgress;
import com.shengqiao.app.core.StreamConfig;

import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

final class AudioEngine {
    interface Listener {
        void onAudioError(String message);
        void onMicrophoneError(long revision, String message);
        default void onPlaybackError(long revision, String message, boolean low) { onAudioError(message); }
        default void onStreamFailure(long revision, String reason) { onAudioError(reason); }
        default void onOutputReady(long revision) {}
    }

    static final int SAMPLE_RATE = 48000;
    private static final int FRAMES = 480;
    private final Context context;
    private final Listener listener;
    private final PcmJitterBuffer jitter = new PcmJitterBuffer(70);
    private final Object lock = new Object();
    private volatile float volume = 1.0f;
    private final AudioStreamReceiver receiver;
    private final Playback playback;
    private volatile long revision;
    private volatile boolean shutdown;
    private boolean hasStream;
    private StreamConfig stream;
    private volatile Output output = Output.EMPTY;
    private Microphone microphone;
    private volatile boolean listening;
    private boolean micEnabled;
    private volatile long microphoneRevision;
    private boolean echoCancel = true;

    AudioEngine(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        receiver = new AudioStreamReceiver(jitter, listener::onStreamFailure);
        playback = new Playback();
        receiver.start();
        playback.start();
    }

    void setListening(boolean enabled) {
        synchronized (lock) {
            if (listening == enabled) return;
            listening = enabled;
            setStream(enabled, null);
        }
    }

    void setStream(boolean present, StreamConfig config) {
        synchronized (lock) {
            hasStream = present && listening;
            stream = hasStream ? config : null;
            receiver.reset(++revision, stream, hasStream);
            output = Output.EMPTY;
            lock.notifyAll();
        }
    }

    boolean isCurrent(long value) { return revision == value; }

    Output output() {
        Output current = output;
        return current.revision == revision ? current : Output.EMPTY;
    }

    void setMicrophone(boolean enabled, boolean echo, BridgeClient client) {
        synchronized (lock) {
            if (micEnabled == enabled && (!enabled || echoCancel == echo)) return;
            microphoneRevision++;
            Microphone previous = microphone;
            if (microphone != null) {
                microphone.close();
            }
            micEnabled = enabled;
            echoCancel = echo;
            if (enabled) {
                microphone = new Microphone(client, echo, microphoneRevision, previous);
                microphone.start();
            }
        }
    }

    void setEchoCancel(boolean echo, BridgeClient client) {
        synchronized (lock) {
            if (echo != echoCancel && micEnabled) {
                setMicrophone(true, echo, client);
            }
            echoCancel = echo;
        }
    }

    void offer(byte[] packet) {
        receiver.offer(packet);
    }

    void setTargetMs(int targetMs) {
        jitter.setStandardMs(targetMs);
    }

    void setVolume(float volume) {
        this.volume = volume;
    }

    PcmJitterBuffer jitter() {
        return jitter;
    }

    void close() {
        synchronized (lock) {
            setListening(false);
            micEnabled = false;
            microphoneRevision++;
            if (microphone != null) {
                microphone.close();
            }
        }
    }

    boolean isCurrentMicrophone(long value) { return microphoneRevision == value; }

    void shutdown() {
        close();
        synchronized (lock) {
            shutdown = true;
            lock.notifyAll();
        }
        receiver.shutdown();
    }

    static final class Output {
        static final Output EMPTY = new Output(-1, 0, false, -1, 0, 0, 0, false, 0, 0, "");
        final long revision;
        final int streamId;
        final boolean low;
        final int queuedMs;
        final int capacity;
        final int effective;
        final int underruns;
        final boolean fastPath;
        final int nativeRate;
        final int nativeQuantum;
        final String route;

        Output(long revision, int streamId, boolean low, int queuedMs, int capacity, int effective,
               int underruns, boolean fastPath, int nativeRate, int nativeQuantum, String route) {
            this.revision = revision;
            this.streamId = streamId;
            this.low = low;
            this.queuedMs = queuedMs;
            this.capacity = capacity;
            this.effective = effective;
            this.underruns = underruns;
            this.fastPath = fastPath;
            this.nativeRate = nativeRate;
            this.nativeQuantum = nativeQuantum;
            this.route = route;
        }
    }

    private final class Playback extends Thread {
        Playback() {
            super("shengqiao-playback");
        }

        private AudioTrack createTrack(int capacity) {
            int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minimum, capacity * 4);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            return new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            short[] block = new short[FRAMES * 2];
            long session = -1;
            try {
                while (true) {
                    StreamConfig current;
                    synchronized (lock) {
                        while (!shutdown && (!hasStream || session == revision)) lock.wait();
                        if (shutdown) return;
                        session = revision;
                        current = stream;
                    }
                    boolean low = current != null && current.low;
                    try {
                        play(session, current == null ? 0 : current.id, low, block);
                    } catch (RuntimeException error) {
                        if (revision == session) listener.onPlaybackError(session,
                                error.getMessage() == null ? "音频播放失败" : error.getMessage(), low);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void play(long session, int streamId, boolean low, short[] block) {
            AudioManager manager = context.getSystemService(AudioManager.class);
            int nativeRate = property(manager, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE, SAMPLE_RATE);
            int quantum = property(manager, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER, FRAMES);
            int quantum48 = Math.max(1, Math.round(quantum * (float) SAMPLE_RATE / nativeRate));
            AudioTrack track = createTrack(low ? Math.max(4 * quantum48, 2 * FRAMES) : 2 * FRAMES);
            try {
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IllegalStateException("系统无法创建音频输出");
                }
                int initial = Math.min(track.getBufferCapacityInFrames(), Math.max(FRAMES, 2 * quantum48));
                int effective = low ? track.setBufferSizeInFrames(initial) : track.getBufferSizeInFrames();
                if (effective < FRAMES) {
                    effective = track.setBufferSizeInFrames(track.getBufferCapacityInFrames());
                    if (effective < FRAMES) throw new IllegalStateException("系统拒绝播放缓冲设置");
                }
                int underruns = 0;
                int routeUnderruns = 0;
                int accumulatedUnderruns = 0;
                int routeId = -1;
                String routeName = "";
                long writtenFrames = 0;
                int lastHead = 0;
                long playedFrames = 0;
                boolean headValid = true;
                long nextSample = 0;
                boolean ready = false;
                boolean primed = false;
                if (revision != session) return;
                track.play();
                while (revision == session) {
                    if (!primed) primed = track.getPlaybackHeadPosition() != 0;
                    boolean hasAudio = false;
                    synchronized (lock) {
                        if (revision != session) break;
                        if (primed) {
                            jitter.render(volume, block);
                            hasAudio = !jitter.isBuffering();
                        } else {
                            Arrays.fill(block, (short) 0);
                        }
                    }
                    int offset = 0;
                    AudioIoProgress progress = new AudioIoProgress(System.nanoTime());
                    while (revision == session && offset < block.length) {
                        int written = track.write(block, offset, block.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                        progress.record(written, System.nanoTime());
                        if (written == 0) {
                            LockSupport.parkNanos(1_000_000L);
                            continue;
                        }
                        offset += written;
                    }
                    if (revision != session) break;
                    writtenFrames += offset / 2;
                    long now = System.nanoTime();
                    if (now < nextSample && (ready || !hasAudio)) continue;
                    nextSample = now + 100_000_000L;
                    int head = track.getPlaybackHeadPosition();
                    long advanced = Integer.toUnsignedLong(head - lastHead);
                    if (advanced > writtenFrames - playedFrames) headValid = false;
                    else playedFrames += advanced;
                    lastHead = head;
                    AudioDeviceInfo device = track.getRoutedDevice();
                    int nextRoute = device == null ? -1 : device.getId();
                    if (nextRoute != -1 && nextRoute != routeId) {
                        if (routeId != -1 && nextRoute != -1) {
                            synchronized (lock) {
                                if (revision != session) break;
                                jitter.resetForRoute();
                            }
                            track.pause();
                            track.flush();
                            track.play();
                            writtenFrames = playedFrames = 0;
                            lastHead = 0;
                            headValid = true;
                            primed = ready = false;
                            accumulatedUnderruns = underruns;
                            routeUnderruns = track.getUnderrunCount();
                        }
                        routeId = nextRoute;
                        routeName = device == null ? "" : device.getProductName().toString();
                        effective = track.getBufferSizeInFrames();
                        if (low) {
                            int resized = track.setBufferSizeInFrames(initial);
                            if (resized >= FRAMES) effective = resized;
                        }
                    }
                    int count = accumulatedUnderruns + Math.max(0, track.getUnderrunCount() - routeUnderruns);
                    if (low && ready && count > underruns && effective < track.getBufferCapacityInFrames()) {
                        int resized = track.setBufferSizeInFrames(effective + quantum48);
                        if (resized >= FRAMES) effective = resized;
                    }
                    underruns = count;
                    long queued = writtenFrames - playedFrames;
                    int capacity = track.getBufferCapacityInFrames();
                    int queuedMs = headValid && playedFrames > 0 && queued <= capacity + FRAMES
                            ? (int) (queued / 48) : -1;
                    output = new Output(session, streamId, low && (ready || hasAudio), queuedMs, capacity,
                            effective, underruns, track.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
                            nativeRate, quantum, routeName);
                    if (!ready && hasAudio) {
                        ready = true;
                        listener.onOutputReady(session);
                    }
                }
            } finally {
                try { track.pause(); } catch (RuntimeException ignored) {}
                try { track.flush(); } catch (RuntimeException ignored) {}
                track.release();
            }
        }

        private int property(AudioManager manager, String key, int fallback) {
            try {
                int value = Integer.parseInt(manager.getProperty(key));
                return value > 0 && value < 384000 ? value : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    private final class Microphone extends Thread {
        private final BridgeClient client;
        private AudioRecord record;
        private final long session;
        private Microphone previous;
        private final boolean useEchoCancel;
        private volatile boolean running = true;
        private boolean released;

        Microphone(BridgeClient client, boolean echoCancel, long session, Microphone previous) {
            super("shengqiao-microphone");
            this.client = client;
            this.session = session;
            this.previous = previous;
            this.useEchoCancel = echoCancel;
        }

        private AudioRecord createRecord(int source, int minimum) {
            AudioRecord result = buildRecord(source, minimum);
            if (source == MediaRecorder.AudioSource.UNPROCESSED
                    && result.getState() != AudioRecord.STATE_INITIALIZED) {
                result.release();
                result = buildRecord(MediaRecorder.AudioSource.MIC, minimum);
            }
            return result;
        }

        private AudioRecord buildRecord(int source, int minimum) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("没有麦克风权限");
            }
            return new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minimum, FRAMES * 2 * 4))
                    .build();
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            short[] samples = new short[FRAMES];
            try {
                if (previous != null) {
                    previous.join(1000);
                    if (previous.isAlive()) throw new IllegalStateException("旧麦克风线程未结束");
                    previous = null;
                }
                if (!running) return;
                int source = useEchoCancel ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        : MediaRecorder.AudioSource.UNPROCESSED;
                int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                record = createRecord(source, minimum);
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("系统无法打开 48 kHz 麦克风");
                }
                record.startRecording();
                while (running) {
                    int offset = 0;
                    AudioIoProgress progress = new AudioIoProgress(System.nanoTime());
                    while (running && offset < samples.length) {
                        int count = record.read(samples, offset, samples.length - offset, AudioRecord.READ_NON_BLOCKING);
                        progress.record(count, System.nanoTime());
                        if (count == 0) LockSupport.parkNanos(1_000_000L);
                        offset += count;
                    }
                    if (running) {
                        client.sendMicrophone(samples, samples.length, 1.0f);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                if (running) {
                    recordError(e.getMessage());
                }
            } finally {
                releaseRecord();
            }
        }

        private void recordError(String message) {
            listener.onMicrophoneError(session, message == null ? "麦克风启动失败" : message);
        }

        void close() {
            running = false;
        }

        private synchronized void releaseRecord() {
            if (released || record == null) {
                return;
            }
            released = true;
            try {
                record.stop();
            } catch (RuntimeException ignored) {
            }
            record.release();
        }
    }
}
