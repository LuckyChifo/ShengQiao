package com.shengqiao.app.ui.common;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.shengqiao.app.R;
import com.shengqiao.app.session.BridgeState;

public final class SessionUi {
    private SessionUi() {
    }

    public static String deviceName(Context context, BridgeState state) {
        if (!state.pcName.isEmpty()) {
            return state.pcName;
        }
        if (!state.host.isEmpty()) {
            return state.host;
        }
        return context.getString(R.string.unknown_computer);
    }

    public static String statusText(Context context, BridgeState state) {
        String label;
        if (!state.serviceAvailable) {
            label = context.getString(R.string.state_service_unavailable);
        } else {
            switch (state.connection) {
                case CONNECTING:
                    label = context.getString(R.string.state_connecting);
                    break;
                case CONNECTED:
                    label = context.getString(R.string.state_connected, deviceName(context, state));
                    break;
                case NEED_KEY:
                    label = context.getString(R.string.state_need_key);
                    break;
                case ERROR:
                    label = context.getString(state.reconnecting ? R.string.state_reconnecting
                            : R.string.state_error);
                    break;
                default:
                    label = context.getString(R.string.state_idle);
            }
        }
        if (state.connection == BridgeState.Connection.ERROR && !state.lastError.isEmpty()
                && !state.lastError.equals(label)) {
            return label + "\n" + state.lastError;
        }
        return label;
    }

    public static int statusColor(Context context, BridgeState state) {
        int color;
        if (state.isConnected()) {
            color = R.color.sq_success;
        } else if (state.connection == BridgeState.Connection.ERROR && state.reconnecting) {
            color = R.color.sq_warning;
        } else if (state.connection == BridgeState.Connection.ERROR
                || state.connection == BridgeState.Connection.NEED_KEY) {
            color = R.color.sq_error;
        } else {
            color = R.color.sq_text_secondary;
        }
        return ContextCompat.getColor(context, color);
    }

    public static String microphoneText(Context context, BridgeState state) {
        if (!state.microphone) {
            return context.getString(R.string.home_microphone_off);
        }
        if (state.micBusy) {
            return context.getString(R.string.home_microphone_busy);
        }
        if (!state.micMine) {
            return context.getString(R.string.home_microphone_waiting);
        }
        StringBuilder value = new StringBuilder(context.getString(R.string.home_microphone_on));
        if (!state.micState.isEmpty() && !"off".equals(state.micState)) {
            value.append('\n').append(context.getString(R.string.home_microphone_remote,
                    state.micState));
        }
        if (!state.micError.isEmpty()) {
            value.append('\n').append(context.getString(R.string.home_microphone_error,
                    state.micError));
        }
        return value.toString();
    }

    public static String latencyText(Context context, BridgeState state) {
        if (state.activeLow) return context.getString(R.string.latency_active);
        if (!state.isConnected()) return context.getString(R.string.latency_disconnected);
        switch (state.fallbackReason) {
            case "server_unsupported": return context.getString(R.string.latency_server_unsupported);
            case "output_unsupported": return context.getString(R.string.latency_output_unsupported);
            case "processing_overload": return context.getString(R.string.latency_processing_overload);
            case "network_unstable": return context.getString(R.string.latency_network_unstable);
            default: return context.getString(state.listening ? R.string.latency_negotiating : R.string.latency_waiting);
        }
    }
}
