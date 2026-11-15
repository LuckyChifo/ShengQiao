package com.shengqiao.app.ui.glass;

import android.view.View;

import com.hihonor.uikit.transparency.window.WindowBlurManager;

final class HonorDialogBlur {
    private HonorDialogBlur() {
    }

    static void apply(View target) {
        WindowBlurManager.setWindowBlur(WindowBlurManager.BLUR_MODE_ONLY_SURFACE, target);
    }

    static void clear(View target) {
        WindowBlurManager.setWindowBlur(WindowBlurManager.BLUR_MODE_NONE, target);
    }
}
