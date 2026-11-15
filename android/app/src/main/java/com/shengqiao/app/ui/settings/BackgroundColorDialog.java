package com.shengqiao.app.ui.settings;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.EditorInfo;
import android.widget.GridLayout;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.shengqiao.app.R;
import com.shengqiao.app.ui.common.GlassPanelDialog;
import com.shengqiao.app.ui.glass.BackgroundSettings;

import java.util.Locale;

final class BackgroundColorDialog {
    interface Listener {
        void onColor(int color);
    }

    private static final int[] COLORS = {
            0xFFF2F6F4, 0xFFE8F0FF, 0xFFE4F3EC, 0xFFF0EAFF,
            0xFFFFF0E5, 0xFFFFE7E7, 0xFFD9E5EC, 0xFFB9D8CE,
            0xFF9FA8DA, 0xFF546E7A, 0xFF263A38, 0xFF101614
    };

    private BackgroundColorDialog() {
    }

    static void show(Fragment owner, int currentColor, Listener listener) {
        Context context = owner.requireContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextInputLayout field = new TextInputLayout(
                new ContextThemeWrapper(context, R.style.ThemeOverlay_ShengQiao_Input));
        field.setHint(context.getText(R.string.settings_background_color_hint));
        TextInputEditText hex = new TextInputEditText(field.getContext());
        hex.setSingleLine();
        hex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        int opaque = currentColor & 0xFFFFFF;
        hex.setText(String.format(Locale.ROOT, "#%06X", opaque));
        field.addView(hex, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        GridLayout palette = new GridLayout(context);
        palette.setColumnCount(4);
        for (int color : COLORS) {
            Button swatch = new Button(context);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(color);
            background.setStroke(dp(context, 1), 0x33777777);
            swatch.setBackground(background);
            String label = String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
            swatch.setContentDescription(label);
            swatch.setOnClickListener(view -> hex.setText(label));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(context, 48);
            params.height = dp(context, 48);
            params.setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
            palette.addView(swatch, params);
        }
        content.addView(palette);
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexParams.setMargins(0, dp(context, 12), 0, 0);
        content.addView(field, hexParams);

        GlassPanelDialog dialog = new GlassPanelDialog(owner, R.string.settings_background_color_title,
                content, android.R.string.ok);
        dialog.setConfirmation(R.string.cancel, () -> {
            try {
                listener.onColor(BackgroundSettings.parseColor(hex.getText().toString()));
                return true;
            } catch (IllegalArgumentException error) {
                field.setError(context.getString(R.string.settings_background_color_invalid));
                hex.requestFocus();
                return false;
            }
        });
        hex.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            dialog.submit();
            return true;
        });
        dialog.show();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
