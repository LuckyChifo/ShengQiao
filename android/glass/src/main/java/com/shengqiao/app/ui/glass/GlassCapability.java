package com.shengqiao.app.ui.glass;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;

public final class GlassCapability {
    private static final String EXTENSION_RESOURCES = "androidhnext";
    private static final int TRANSPARENCY_MODE_SUPPORTED = 3;

    private GlassCapability() {
    }

    public static boolean isSupported(Context context) {
        return getTransparencyMode(context) == TRANSPARENCY_MODE_SUPPORTED;
    }

    @SuppressLint("DiscouragedApi")
    public static int getTransparencyMode(Context context) {
        try {
            Resources resources = context.getApplicationContext().getResources();
            int id = resources.getIdentifier(
                    "launcher_transparency_mode", "integer", EXTENSION_RESOURCES);
            return id > 0 ? resources.getInteger(id) : 0;
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }
}
