package com.shengqiao.app.ui.glass;

import android.content.Context;

public final class GlassSettings {
    private static final String PREFERENCES = "settings";
    private static final String KEY_MODE = "glass_mode";

    private GlassSettings() {
    }

    public static GlassMode getMode(Context context) {
        String value = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_MODE, GlassMode.SYSTEM.storageValue);
        return GlassMode.fromStorage(value);
    }

    public static void setMode(Context context, GlassMode mode) {
        context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.storageValue)
                .apply();
    }
}
