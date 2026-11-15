package com.shengqiao.app.ui.glass;

import android.content.Context;
import android.content.SharedPreferences;

import com.shengqiao.app.glass.R;

public final class BackgroundSettings {
    public static final int TYPE_DEFAULT = 0;
    public static final int TYPE_COLOR = 1;
    public static final int TYPE_IMAGE = 2;

    private static final String PREFERENCES = "settings";
    private static final String KEY_TYPE = "background_type";
    private static final String KEY_COLOR = "background_color";
    private static final String KEY_URI = "background_uri";

    private BackgroundSettings() {
    }

    public static int getType(Context context) {
        return preferences(context).getInt(KEY_TYPE, TYPE_DEFAULT);
    }

    public static int getSolidColor(Context context) {
        int defaultColor = context.getColor(R.color.sq_glass_background_default);
        if (getType(context) != TYPE_COLOR) {
            return defaultColor;
        }
        return preferences(context).getInt(KEY_COLOR, defaultColor);
    }

    public static int getCustomColor(Context context) {
        return preferences(context).getInt(KEY_COLOR, 0xFFF2F6F4);
    }

    public static String getImageUri(Context context) {
        return preferences(context).getString(KEY_URI, null);
    }

    public static void setColor(Context context, int color) {
        preferences(context).edit()
                .putInt(KEY_TYPE, TYPE_COLOR)
                .putInt(KEY_COLOR, color)
                .remove(KEY_URI)
                .apply();
    }

    public static void setImage(Context context, String uri) {
        preferences(context).edit()
                .putInt(KEY_TYPE, TYPE_IMAGE)
                .putString(KEY_URI, uri)
                .apply();
    }

    public static void reset(Context context) {
        preferences(context).edit()
                .remove(KEY_TYPE)
                .remove(KEY_COLOR)
                .remove(KEY_URI)
                .apply();
    }

    public static int parseColor(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.startsWith("#")) {
            normalized = "#" + normalized;
        }
        if (normalized.length() != 7 && normalized.length() != 9) {
            throw new IllegalArgumentException("Expected #RRGGBB or #AARRGGBB");
        }
        long color = Long.parseLong(normalized.substring(1), 16);
        return normalized.length() == 7 ? (int) (0xFF000000L | color) : (int) color;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
