package com.shengqiao.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import com.shengqiao.app.session.BridgeState;
import com.shengqiao.app.core.StreamConfig;

import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BridgeService extends Service {
    public static final int IDLE = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;
    public static final int ERROR = 3;
    public static final int NEED_KEY = 4;
    public static final String ACTION_CONNECT = "com.shengqiao.app.CONNECT";
    static final String ACTION_STOP = "com.shengqiao.app.STOP";

    public interface Listener {
        void onStateChanged(BridgeState state);
    }

    public final class LocalBinder extends Binder {
        public BridgeService getService() {
            return BridgeService.this;
        }
    }

    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIFICATION_ID = 23;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private BridgeClient client;
    private AudioEngine audio;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private String host = "";
    private int port = 23333;
    private String key = "";
    private String pcName = "";
    private String pcId = "";
    private String lastError = "";
    private volatile String micState = "off";
    private volatile String micError = "";
    private boolean micMine;
    private boolean micBusy;
    private volatile String denoise = "rnnoise";
    private int preferredBitrate;
    private int bitrate;
    private boolean opusAvailable;
    private boolean preferLow;
    private boolean streamProtocol;
    private int confirmedStream;
    private int confirmedConfigId;
    private boolean confirmedLow;
    private String fallbackReason = "";
    private String codecFallback = "";
    private boolean forcePcm;
    private long lastLowDisconnect;
    private double codecDelayMs = -1;
    private volatile int state = IDLE;
    private volatile boolean listening;
    private volatile boolean microphone;
    private boolean echoCancel = true;
    private boolean manualClose;
    private boolean reconnectScheduled;
    private int retries;
    private long generation;
    private volatile int rttMs = -1;
    private int targetMs = 70;
    private int volumePercent = 100;
    private Runnable reconnectTask;
    private long wakeLockedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShengQiao:Audio");
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ShengQiao:LowLatency");
        } else {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ShengQiao:Audio");
        }
        audio = new AudioEngine(this, new AudioEngine.Listener() {
            @Override
            public void onAudioError(String message) {
                main.post(() -> stopAfterAudioError(message));
            }

            @Override
            public void onPlaybackError(long revision, String message, boolean low) {
                main.post(() -> {
                    if (!audio.isCurrent(revision) || !listening) return;
                    if (low) fallback("output_unsupported");
                    else stopAfterAudioError(message);
                });
            }

            @Override
            public void onOutputReady(long revision) {
                main.post(() -> {
                    if (audio.isCurrent(revision)) notifyState();
                });
            }

            @Override
            public void onStreamFailure(long revision, String reason) {
                main.post(() -> {
                    if (!audio.isCurrent(revision) || !listening || state != CONNECTED) return;
                    if ("decoder_error".equals(reason) && preferredBitrate > 0 && !forcePcm) {
                        forcePcm = true;
                        sendConfig();
                    } else if (requestsLow()) {
                        fallback(reason);
                        // Requesting standard creates a new stream. Discard a stalled TCP pipe as well.
                        if ("network_unstable".equals(reason)) client.close();
                    } else {
                        client.close();
                    }
                    notifyState();
                });
            }

            @Override
            public void onMicrophoneError(long revision, String message) {
                main.post(() -> {
                    if (!audio.isCurrentMicrophone(revision) || !microphone) return;
                    microphone = false;
                    lastError = "麦克风启动失败：" + message;
                    audio.setMicrophone(false, echoCancel, client);
                    sendConfig();
                    updateForeground();
                    updateLocks();
                    notifyState();
                });
            }
        });
        android.content.SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        targetMs = preferences.getInt("target", 70);
        echoCancel = preferences.getBoolean("echo", true);
        volumePercent = Math.round(preferences.getFloat("volume", 1.0f) * 100);
        preferredBitrate = preferences.getInt("bitrate", 0);
        preferLow = preferences.getBoolean("lowLatency", false);
        audio.setTargetMs(targetMs);
        audio.setVolume(volumePercent / 100.0f);
        client = new BridgeClient(new BridgeClient.Listener() {
            @Override
            public void onOpen() {
                state = CONNECTING;
                lastError = "";
                notifyState();
            }

            @Override
            public void onHello(JSONObject message) {
                pcName = message.optString("name", host);
                pcId = message.optString("id", "");
                state = CONNECTED;
                streamProtocol = StreamConfig.supported(message);
                opusAvailable = message.optJSONArray("bitrates") != null
                        && message.optJSONArray("bitrates").length() > 0;
                retries = 0;
                lastError = "";
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putString("pcId", pcId).putString("pcName", pcName).apply();
                sendConfig();
                updateForeground();
                notifyState();
            }

            @Override
            public void onMessage(JSONObject message) {
                if ("pong".equals(message.optString("type"))) {
                    rttMs = (int) Math.max(0, System.nanoTime() / 1_000_000.0 - message.optDouble("t"));
                } else if ("status".equals(message.optString("type"))) {
                    if (streamProtocol && message.optInt("configId", -1) != client.configId()) return;
                    bitrate = message.optInt("bitrate", 0);
                    confirmedStream = message.optInt("streamId", 0);
                    confirmedConfigId = message.optInt("configId", 0);
                    confirmedLow = "low".equals(message.optString("latencyMode"));
                    codecDelayMs = message.optDouble("codecDelayMs", bitrate > 0 ? -1 : 0);
                    codecFallback = message.optString("codecFallbackReason", "");
                    if (message.isNull("codecFallbackReason")) codecFallback = "";
                    opusAvailable = message.optJSONArray("bitrates") != null
                            && message.optJSONArray("bitrates").length() > 0;
                    JSONObject mic = message.optJSONObject("mic");
                    if (mic != null) {
                        micBusy = mic.optBoolean("busy", false);
                        micMine = mic.optBoolean("mine", !micBusy);
                        micState = mic.optString("state", "off");
                        micError = mic.optString("error", "");
                        denoise = mic.optString("denoise", denoise);
                    }
                    notifyState();
                }
            }

            @Override
            public void onAudio(byte[] packet) {
                audio.offer(packet);
            }

            @Override
            public void onStream(StreamConfig stream) {
                audio.setStream(true, stream);
            }

            @Override
            public void onLegacyStream() {
                audio.setStream(true, null);
            }

            @Override
            public void onStreamReset() {
                audio.setStream(false, null);
            }

            @Override
            public void onClosed(String reason, boolean badKey) {
                handleClosed(reason, badKey);
            }
        }, main::post, deviceName());
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_CONNECT.equals(intent.getAction())) {
            connect(intent.getStringExtra("host"), intent.getIntExtra("port", 23333),
                    intent.getStringExtra("key"));
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void connect(String address, int portNumber, String accessKey) {
        generation++;
        host = address == null ? "" : address.trim();
        port = portNumber;
        key = accessKey == null ? "" : accessKey.trim();
        if (host.isEmpty()) {
            lastError = "请填写电脑 IP 地址";
            state = ERROR;
            notifyState();
            return;
        }
        manualClose = true;
        client.disconnect();
        cancelReconnect();
        retries = 0;
        reconnectScheduled = false;
        pcName = host;
        lastError = "";
        state = CONNECTING;
        resetMicStatus();
        resetLatencySession();
        updateForeground();
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("host", host).putInt("port", port).putString("key", key).apply();
        client.connect(host, port, key);
        notifyState();
        main.post(() -> manualClose = false);
    }

    public void disconnect() {
        generation++;
        state = IDLE;
        manualClose = true;
        client.disconnect();
        manualClose = false;
        cancelReconnect();
        listening = false;
        microphone = false;
        audio.close();
        resetLatencySession();
        lastError = "";
        resetMicStatus();
        releaseLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyState();
    }

    public void setListening(boolean enabled) {
        if (enabled && state != CONNECTED) {
            return;
        }
        if (listening == enabled) return;
        listening = enabled;
        fallbackReason = "";
        forcePcm = false;
        lastLowDisconnect = 0;
        try {
            audio.setListening(enabled);
        } catch (RuntimeException e) {
            listening = false;
            lastError = "音频启动失败：" + e.getMessage();
        }
        sendConfig();
        updateForeground();
        updateLocks();
        notifyState();
    }

    public void setMicrophone(boolean enabled, boolean echo) {
        if (enabled && (state != CONNECTED
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)) {
            return;
        }
        if (microphone == enabled) return;
        microphone = enabled;
        if (!enabled) resetMicStatus();
        echoCancel = echo;
        updateForeground();
        try {
            audio.setMicrophone(enabled, echo, client);
        } catch (RuntimeException e) {
            microphone = false;
            lastError = "麦克风启动失败：" + e.getMessage();
            updateForeground();
        }
        sendConfig();
        updateLocks();
        notifyState();
    }

    public void setEchoCancel(boolean enabled) {
        echoCancel = enabled;
        if (microphone) {
            try {
                audio.setEchoCancel(enabled, client);
            } catch (RuntimeException e) {
                lastError = "切换回声消除失败：" + e.getMessage();
            }
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("echo", enabled).apply();
        notifyState();
    }

    public void setTargetMs(int milliseconds) {
        if (targetMs == milliseconds || (milliseconds != 40 && milliseconds != 70 && milliseconds != 150)) return;
        targetMs = milliseconds;
        audio.setTargetMs(milliseconds);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("target", milliseconds).apply();
        notifyState();
    }

    public void setBitrate(int value) {
        if (value != 0 && value != 64 && value != 128 && value != 256 && value != 320) {
            return;
        }
        if (preferredBitrate == value) return;
        forcePcm = false;
        preferredBitrate = value;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("bitrate", value).apply();
        sendConfig();
        notifyState();
    }

    public void setVolume(float value) {
        float bounded = Math.max(0, Math.min(2, value));
        volumePercent = Math.round(bounded * 100);
        audio.setVolume(bounded);
        getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("volume", bounded).apply();
        notifyState();
    }

    public void setDenoise(String mode) {
        denoise = mode;
        client.setDenoise(mode);
        notifyState();
    }

    public void setLowLatency(boolean enabled) {
        if (preferLow == enabled) return;
        preferLow = enabled;
        fallbackReason = "";
        lastLowDisconnect = 0;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("lowLatency", enabled).apply();
        sendConfig();
        notifyState();
    }

    private boolean requestsLow() {
        return preferLow && streamProtocol && fallbackReason.isEmpty();
    }

    private void fallback(String reason) {
        fallbackReason = reason;
        sendConfig();
        notifyState();
    }

    private void stopAfterAudioError(String message) {
        lastError = message;
        setListening(false);
    }

    private void resetLatencySession() {
        streamProtocol = false;
        confirmedStream = 0;
        confirmedConfigId = 0;
        confirmedLow = false;
        codecDelayMs = -1;
        codecFallback = "";
        lastLowDisconnect = 0;
        fallbackReason = "";
        forcePcm = false;
        rttMs = -1;
    }

    private void sendConfig() {
        if (state != CONNECTED) return;
        client.setAudioState(listening, microphone, forcePcm ? 0 : preferredBitrate,
                requestsLow() ? "low" : "standard");
    }

    private void resetMicStatus() {
        micState = "off";
        micError = "";
        micMine = false;
        micBusy = false;
    }

    private String deviceName() {
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name == null || name.trim().isEmpty()) {
            name = Build.MANUFACTURER + " " + Build.MODEL;
        }
        return name.trim();
    }

    private void handleClosed(String reason, boolean badKey) {
        if (manualClose || state == IDLE) {
            return;
        }
        boolean hadLowStream = confirmedLow;
        resetMicStatus();
        confirmedStream = 0;
        confirmedLow = false;
        if (badKey) {
            state = NEED_KEY;
            lastError = "访问码不正确";
            cancelReconnect();
            listening = false;
            microphone = false;
            audio.close();
            releaseLocks();
            updateForeground();
        } else {
            audio.setStream(false, null);
            if (listening && requestsLow() && hadLowStream) {
                long now = SystemClock.elapsedRealtime();
                if (lastLowDisconnect != 0 && now - lastLowDisconnect < 10_000) {
                    fallbackReason = "network_unstable";
                }
                lastLowDisconnect = now;
            }
            state = ERROR;
            lastError = reason;
            scheduleReconnect();
        }
        notifyState();
    }

    private void scheduleReconnect() {
        if (reconnectScheduled || host.isEmpty()) {
            return;
        }
        reconnectScheduled = true;
        long delay = Math.min(3000, (long) (500 * Math.pow(1.6, retries++)));
        reconnectTask = () -> {
            reconnectScheduled = false;
            reconnectTask = null;
            if (state == IDLE || state == NEED_KEY || state == CONNECTED) {
                return;
            }
            state = CONNECTING;
            client.connect(host, port, key);
            notifyState();
        };
        main.postDelayed(reconnectTask, delay);
    }

    private void cancelReconnect() {
        if (reconnectTask != null) {
            main.removeCallbacks(reconnectTask);
            reconnectTask = null;
            reconnectScheduled = false;
        }
    }

    private void tick() {
        if (listening || microphone) {
            updateLocks();
        }
        if (!client.isOpen() || state == IDLE) {
            return;
        }
        if (client.millisecondsSinceMessage() > 5000) {
            client.close();
            return;
        }
        client.ping(System.nanoTime() / 1_000_000.0);
        if (listening) {
            client.stats(audio.jitter().bufferedMs(), audio.jitter().targetMs(),
                    audio.jitter().underruns(), rttMs);
        }
        main.post(() -> {
            if (state == CONNECTED && listening && requestsLow() && audio.output().low
                    && audio.jitter().isUnstable()) fallback("network_unstable");
            else notifyState();
        });
    }

    private void updateForeground() {
        String text = state == CONNECTED ? "已连接 " + pcName : "正在连接电脑";
        if (listening && microphone) {
            text = "正在收听并使用麦克风 · 息屏保持运行";
        } else if (listening) {
            text = "正在收听电脑 · 息屏保持运行";
        } else if (microphone) {
            text = "正在使用手机麦克风 · 息屏保持运行";
        } else if (state == NEED_KEY) {
            text = lastError;
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPending = PendingIntent.getService(this, 1,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("声桥")
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(Color.rgb(101, 214, 173))
                .addAction(new Notification.Action.Builder(null, "断开", stopPending).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (microphone) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private synchronized void updateLocks() {
        if ((listening || microphone) && state != IDLE) {
            long now = SystemClock.elapsedRealtime();
            if (!wakeLock.isHeld() || now - wakeLockedAt > TimeUnit.MINUTES.toMillis(5)) {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
                wakeLock.acquire(TimeUnit.MINUTES.toMillis(10));
                wakeLockedAt = now;
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } else {
            releaseLocks();
        }
    }

    private synchronized void releaseLocks() {
        wakeLockedAt = 0;
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "声桥音频连接",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持电脑声音和手机麦克风在息屏时运行");
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void notifyState() {
        BridgeState snapshot = getSnapshot();
        for (Listener current : listeners) {
            current.onStateChanged(snapshot);
        }
    }

    public BridgeState getSnapshot() {
        BridgeState.Connection connection;
        switch (state) {
            case CONNECTING:
                connection = BridgeState.Connection.CONNECTING;
                break;
            case CONNECTED:
                connection = BridgeState.Connection.CONNECTED;
                break;
            case ERROR:
                connection = BridgeState.Connection.ERROR;
                break;
            case NEED_KEY:
                connection = BridgeState.Connection.NEED_KEY;
                break;
            default:
                connection = BridgeState.Connection.IDLE;
        }
        AudioEngine.Output output = audio.output();
        boolean activeLow = state == CONNECTED && listening && requestsLow() && confirmedLow
                && output.low && output.streamId == confirmedStream && confirmedConfigId == client.configId();
        String reason = fallbackReason;
        if (state == CONNECTED && preferLow && !streamProtocol) reason = "server_unsupported";
        return new BridgeState(true, connection, reconnectScheduled, generation, pcName, pcId,
                host, port, lastError, listening, microphone, echoCancel, isBuffering(),
                volumePercent, targetMs, rttMs, getBufferedMs(), getActualTargetMs(),
                getUnderruns(), getLostFrames(), micState, micError, micMine, micBusy, denoise, bitrate, opusAvailable,
                preferLow, activeLow, reason, forcePcm ? "decoder_error" : codecFallback,
                !listening || output.revision < 0 ? -1 : codecDelayMs,
                output.queuedMs, output.capacity, output.effective, output.underruns,
                output.fastPath, output.nativeRate, output.nativeQuantum, output.route, audio.jitter().skips());
    }

    @Override
    public void onDestroy() {
        state = IDLE;
        cancelReconnect();
        client.shutdown();
        audio.shutdown();
        releaseLocks();
        scheduler.shutdownNow();
        listeners.clear();
        super.onDestroy();
    }

    int getState() { return state; }
    boolean isListening() { return listening; }
    boolean isMicrophone() { return microphone; }
    boolean isEchoCancel() { return echoCancel; }
    int getTargetMs() { return targetMs; }
    int getRttMs() { return rttMs; }
    int getBufferedMs() { return audio.jitter().bufferedMs(); }
    int getActualTargetMs() { return audio.jitter().targetMs(); }
    int getUnderruns() { return audio.jitter().underruns(); }
    int getLostFrames() { return audio.jitter().lostFrames(); }
    boolean isBuffering() { return audio.jitter().isBuffering(); }
    String getPcName() { return pcName; }
    String getPcId() { return pcId; }
    String getLastError() { return lastError; }
    String getMicState() { return micState; }
    String getMicError() { return micError; }
    String getDenoise() { return denoise; }
    String getHost() { return host; }
    int getPort() { return port; }
    String getKey() { return key; }
}
