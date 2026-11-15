package com.shengqiao.app.ui.home;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;

import com.shengqiao.app.R;
import com.shengqiao.app.session.BridgeState;
import com.shengqiao.app.ui.common.GlassPanelDialog;
import com.shengqiao.app.ui.common.SessionUi;

import java.util.ArrayList;
import java.util.List;

final class ConnectionDetailsDialog {
    private ConnectionDetailsDialog() {
    }

    static void show(HomeFragment owner, BridgeState state) {
        Context context = owner.requireContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        GlassPanelDialog dialog = new GlassPanelDialog(owner, R.string.home_details_title,
                content, R.string.close);

        addSection(content, dialog, R.string.home_details_network_group,
                context.getString(R.string.home_details_device, SessionUi.deviceName(context, state)),
                context.getString(R.string.home_details_address, state.host, state.port),
                context.getString(R.string.home_details_rtt, milliseconds(context, state.rttMs)),
                context.getString(R.string.home_details_lost, state.lostFrames));

        List<String> playback = new ArrayList<>();
        playback.add(context.getString(R.string.home_details_mode,
                context.getString(state.preferLow ? R.string.latency_short_low : R.string.latency_standard),
                state.preferLow ? SessionUi.latencyText(context, state)
                        : context.getString(R.string.latency_standard)));
        playback.add(context.getString(R.string.home_details_buffer, state.bufferedMs, state.actualTargetMs));
        playback.add(state.bitrate > 0 ? context.getString(R.string.home_details_bitrate_opus, state.bitrate)
                : context.getString(R.string.home_details_bitrate_pcm));
        playback.add(context.getString(R.string.home_details_codec_delay, state.codecDelayMs < 0
                ? context.getString(R.string.not_measured)
                : context.getString(R.string.fractional_milliseconds, state.codecDelayMs)));
        playback.add(context.getString(R.string.home_details_underruns, state.underruns));
        playback.add(context.getString(R.string.home_details_catchups, state.catchups));
        if (!state.codecFallback.isEmpty()) {
            int reason = switch (state.codecFallback) {
                case "opus_unavailable" -> R.string.codec_no_opus;
                case "decoder_error" -> R.string.codec_decoder;
                default -> R.string.codec_encoder;
            };
            playback.add(context.getString(reason));
        }
        if (state.buffering && state.listening) {
            playback.add(context.getString(R.string.home_details_buffering));
        }
        addSection(content, dialog, R.string.home_details_playback_group,
                playback.toArray(new String[0]));

        if (state.outputCapacity > 0) {
            addSection(content, dialog, R.string.home_details_output_group,
                    context.getString(R.string.home_details_output, milliseconds(context, state.queuedMs),
                            state.outputBuffer, state.outputCapacity, state.outputUnderruns),
                    context.getString(R.string.home_details_route,
                            state.outputRoute.isEmpty() ? context.getString(R.string.not_measured) : state.outputRoute,
                            state.nativeRate, state.nativeQuantum, context.getString(state.outputFastPath
                                    ? R.string.latency_short_low : R.string.latency_standard)));
        }
        if (!state.lastError.isEmpty()) {
            addSection(content, dialog, R.string.home_details_status_group,
                    context.getString(R.string.home_details_error, state.lastError));
        }
        dialog.show();
    }

    private static void addSection(LinearLayout content, GlassPanelDialog dialog,
                                   @StringRes int title, String... rows) {
        Context context = content.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        TextView heading = new TextView(context);
        heading.setTextAppearance(R.style.TextAppearance_ShengQiao_Section);
        heading.setText(title);
        if (content.getChildCount() > 0) {
            heading.setPadding(0, Math.round(16 * density), 0, 0);
        }
        ViewCompat.setAccessibilityHeading(heading, true);
        content.addView(heading);
        dialog.addForegroundText(heading);

        for (String row : rows) {
            TextView text = new TextView(context);
            text.setTextAppearance(R.style.TextAppearance_ShengQiao_Body);
            text.setPadding(0, Math.round(4 * density), 0, 0);
            text.setText(row);
            content.addView(text);
            dialog.addForegroundText(text);
        }
    }

    private static String milliseconds(Context context, int value) {
        return value < 0 ? context.getString(R.string.not_measured)
                : context.getString(R.string.milliseconds, value);
    }
}
