package com.shengqiao.app.session;

public final class BridgeState {
    public enum Connection {
        IDLE,
        CONNECTING,
        CONNECTED,
        ERROR,
        NEED_KEY
    }

    public final boolean serviceAvailable;
    public final Connection connection;
    public final boolean reconnecting;
    public final long generation;
    public final String pcName;
    public final String pcId;
    public final String host;
    public final int port;
    public final String lastError;
    public final boolean listening;
    public final boolean microphone;
    public final boolean echoCancel;
    public final boolean buffering;
    public final int volumePercent;
    public final int targetMs;
    public final int rttMs;
    public final int bufferedMs;
    public final int actualTargetMs;
    public final int underruns;
    public final int lostFrames;
    public final String micState;
    public final String micError;
    public final boolean micMine;
    public final boolean micBusy;
    public final String denoise;
    public final int bitrate;
    public final boolean opusAvailable;
    public final boolean preferLow;
    public final boolean activeLow;
    public final String fallbackReason;
    public final String codecFallback;
    public final double codecDelayMs;
    public final int queuedMs;
    public final int outputCapacity;
    public final int outputBuffer;
    public final int outputUnderruns;
    public final boolean outputFastPath;
    public final int nativeRate;
    public final int nativeQuantum;
    public final String outputRoute;
    public final int catchups;

    public BridgeState(boolean serviceAvailable, Connection connection, boolean reconnecting,
                       long generation, String pcName, String pcId, String host, int port,
                       String lastError, boolean listening, boolean microphone, boolean echoCancel,
                       boolean buffering, int volumePercent, int targetMs, int rttMs, int bufferedMs,
                       int actualTargetMs, int underruns, int lostFrames, String micState,
                       String micError, boolean micMine, boolean micBusy, String denoise, int bitrate, boolean opusAvailable,
                       boolean preferLow, boolean activeLow, String fallbackReason, String codecFallback,
                       double codecDelayMs, int queuedMs, int outputCapacity, int outputBuffer,
                       int outputUnderruns, boolean outputFastPath, int nativeRate, int nativeQuantum, String outputRoute,
                       int catchups) {
        this.serviceAvailable = serviceAvailable;
        this.connection = connection;
        this.reconnecting = reconnecting;
        this.generation = generation;
        this.pcName = text(pcName);
        this.pcId = text(pcId);
        this.host = text(host);
        this.port = port;
        this.lastError = text(lastError);
        this.listening = listening;
        this.microphone = microphone;
        this.echoCancel = echoCancel;
        this.buffering = buffering;
        this.volumePercent = volumePercent;
        this.targetMs = targetMs;
        this.rttMs = rttMs;
        this.bufferedMs = bufferedMs;
        this.actualTargetMs = actualTargetMs;
        this.underruns = underruns;
        this.lostFrames = lostFrames;
        this.micState = text(micState);
        this.micError = text(micError);
        this.micMine = micMine;
        this.micBusy = micBusy;
        this.denoise = text(denoise);
        this.bitrate = bitrate;
        this.opusAvailable = opusAvailable;
        this.preferLow = preferLow;
        this.activeLow = activeLow;
        this.fallbackReason = fallbackReason;
        this.codecFallback = codecFallback;
        this.codecDelayMs = codecDelayMs;
        this.queuedMs = queuedMs;
        this.outputCapacity = outputCapacity;
        this.outputBuffer = outputBuffer;
        this.outputUnderruns = outputUnderruns;
        this.outputFastPath = outputFastPath;
        this.nativeRate = nativeRate;
        this.nativeQuantum = nativeQuantum;
        this.outputRoute = outputRoute;
        this.catchups = catchups;
    }

    public static BridgeState unavailable() {
        return new BridgeState(false, Connection.IDLE, false, 0, "", "", "", 23333,
                "", false, false, true, false, 100, 70, -1, 0, 0, 0, 0,
                "off", "", false, false, "rnnoise", 0, false, false, false, "", "",
                -1, -1, 0, 0, 0, false, 0, 0, "", 0);
    }

    public boolean isConnected() {
        return serviceAvailable && connection == Connection.CONNECTED;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
