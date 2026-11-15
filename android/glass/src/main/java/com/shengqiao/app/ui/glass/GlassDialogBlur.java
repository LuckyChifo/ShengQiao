package com.shengqiao.app.ui.glass;

public final class GlassDialogBlur {
    private GlassDialogBlur() {
    }

    public static boolean apply(GlassLayout target) {
        if (!target.isHonorGlassActive()) {
            return false;
        }
        try {
            HonorDialogBlur.apply(target);
            return true;
        } catch (RuntimeException | LinkageError error) {
            clear(target);
            target.activateFallback();
            return false;
        }
    }

    public static void clear(GlassLayout target) {
        if (!GlassCapability.isSupported(target.getContext())) {
            return;
        }
        try {
            HonorDialogBlur.clear(target);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}
