package com.shengqiao.app.core;

import org.json.JSONArray;
import org.json.JSONObject;

public final class StreamConfig {
    public static final String LATENCY_FEATURE = "latency_profile_v1";
    public static final String STREAM_FEATURE = "stream_info_v1";
    public final int id;
    public final int configId;
    public final boolean low;
    public final boolean opus;
    public final int delaySamples;

    private StreamConfig(int id, int configId, boolean low, boolean opus, int delaySamples) {
        this.id = id;
        this.configId = configId;
        this.low = low;
        this.opus = opus;
        this.delaySamples = delaySamples;
    }

    public static boolean supported(JSONObject hello) {
        if (hello.optInt("latencyProfileVersion") != 1) return false;
        JSONArray features = hello.optJSONArray("features");
        boolean latency = false;
        boolean stream = false;
        for (int i = 0; features != null && i < features.length(); i++) {
            latency |= LATENCY_FEATURE.equals(features.optString(i));
            stream |= STREAM_FEATURE.equals(features.optString(i));
        }
        return latency && stream;
    }

    public static StreamConfig parse(JSONObject message) {
        int id = message.optInt("streamId");
        int config = message.optInt("configId");
        String mode = message.optString("latencyMode");
        String codec = message.optString("codec");
        int bitrate = message.optInt("bitrate", -1);
        int delay = message.optInt("codecDelaySamples", -1);
        boolean low = "low".equals(mode);
        if (id <= 0 || config <= 0 || (!low && !"standard".equals(mode))
                || message.optInt("sampleRate") != 48000 || message.optInt("channels") != 2
                || message.optInt("frameSamples") != 480 || message.optLong("firstSequence", -1) != 0) {
            return null;
        }
        if ("pcm".equals(codec) && bitrate == 0 && delay == 0 && message.isNull("opusApplication")) {
            return new StreamConfig(id, config, low, false, delay);
        }
        if ("opus".equals(codec) && (bitrate == 64 || bitrate == 128 || bitrate == 256 || bitrate == 320)
                && message.optInt("opusApplication") == (low ? 2051 : 2048)
                && delay >= 0 && delay <= 4800) {
            return new StreamConfig(id, config, low, true, delay);
        }
        return null;
    }

    public boolean accepts(byte[] packet) {
        return packet != null && packet.length >= 8 && packet[2] == 0 && packet[3] == 0
                && (opus ? AudioPacket.isOpus(packet) && packet.length <= 1283
                         : AudioPacket.isPcm(packet) && packet.length == 1928);
    }
}
