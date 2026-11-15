package com.shengqiao.app.ui.glass;

import android.content.Context;
import android.os.Bundle;

import com.hihonor.uikit.transparency.api.HnGlassEffect;
import com.hihonor.uikit.transparency.api.HnMaterialType;
import com.hihonor.uikit.transparency.api.HnSceneType;
import com.hihonor.uikit.transparency.api.HnVisualEffectLayout;
import com.hihonor.uikit.transparency.api.HnWidgetSize;
import com.hihonor.uikit.transparency.api.IColorChangedReceiver;

final class HonorGlassBackend {
    private final GlassLayout owner;
    private final HnVisualEffectLayout layout;
    private final IColorChangedReceiver receiver;
    private HnGlassEffect effect;
    private boolean receiverEnabled;
    private boolean receiverRegistered;

    HonorGlassBackend(Context context, GlassLayout owner) {
        this.owner = owner;
        layout = new HnVisualEffectLayout(context);
        receiver = new IColorChangedReceiver() {
            @Override
            public void colorChanged(int type, float fraction, boolean isDarkMode, Bundle bundle) {
                owner.dispatchBackgroundChanged(isDarkMode);
            }
        };
    }

    HnVisualEffectLayout getLayout() {
        return layout;
    }

    void apply(GlassMode mode, GlassScene scene, GlassWidgetSize size, int radiusPx) {
        unregisterReceiver();
        layout.setVisualEffect(null);
        effect = new HnGlassEffect();
        effect.setSceneType(scene == GlassScene.IMMERSIVE
                ? HnSceneType.IMMERSIVE : HnSceneType.GENERAL);
        effect.setWidgetSize(size == GlassWidgetSize.BIG ? HnWidgetSize.BIG : HnWidgetSize.SMALL);
        effect.setAdaptToBackground(true);
        if (mode == GlassMode.SYSTEM) {
            effect.setFollowSystemSettings(true);
        } else {
            effect.setMaterialType(mode == GlassMode.LIQUID
                    ? HnMaterialType.LIQUID_GLASS : HnMaterialType.FROSTED_GLASS);
            effect.setFollowSystemSettings(false);
        }
        effect.setRadius(radiusPx);
        layout.setRadius(radiusPx);
        layout.setVisualEffect(effect);
        updateReceiverRegistration();
    }

    void setReceiverEnabled(boolean enabled) {
        receiverEnabled = enabled;
        updateReceiverRegistration();
    }

    private void updateReceiverRegistration() {
        try {
            if (receiverEnabled && effect != null && !receiverRegistered) {
                effect.addColorChangedReceiver(receiver);
                receiverRegistered = true;
            } else if (!receiverEnabled && receiverRegistered) {
                unregisterReceiver();
            }
        } catch (RuntimeException | LinkageError ignored) {
            receiverRegistered = false;
        }
    }

    private void unregisterReceiver() {
        if (receiverRegistered && effect != null) {
            try {
                effect.removeColorChangedReceiver(receiver);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        receiverRegistered = false;
    }

    void dispose() {
        receiverEnabled = false;
        unregisterReceiver();
        layout.setVisualEffect(null);
        effect = null;
    }
}
