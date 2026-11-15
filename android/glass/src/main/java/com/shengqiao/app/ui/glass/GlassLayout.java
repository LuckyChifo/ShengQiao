package com.shengqiao.app.ui.glass;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.shengqiao.app.glass.R;

import java.util.ArrayList;

public final class GlassLayout extends FrameLayout {
    private static final String TAG = "GlassLayout";

    public interface OnBackgroundChangedListener {
        void onBackgroundChanged(boolean darkBackground);
    }

    private final GradientDrawable fallbackBackground = new GradientDrawable();
    private FrameLayout content;
    private HonorGlassBackend honorBackend;
    private GlassMode mode = GlassMode.SYSTEM;
    private GlassScene scene = GlassScene.GENERAL;
    private GlassWidgetSize widgetSize = GlassWidgetSize.SMALL;
    private int radiusPx;
    private boolean attached;
    private OnBackgroundChangedListener backgroundListener;

    public GlassLayout(Context context) {
        super(context);
        initialize();
    }

    public GlassLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public GlassLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    private void initialize() {
        fallbackBackground.setColor(getContext().getColor(R.color.sq_glass_fallback));
        if (GlassCapability.isSupported(getContext())) {
            try {
                honorBackend = new HonorGlassBackend(getContext(), this);
                content = honorBackend.getLayout();
                setBackground(null);
            } catch (RuntimeException | LinkageError error) {
                Log.w(TAG, "Unable to create Honor glass container", error);
                honorBackend = null;
                content = null;
            }
        }
        if (content == null) {
            createFallbackContent();
        }
        super.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyHonorEffect();
    }

    private void createFallbackContent() {
        content = new FrameLayout(getContext());
        setBackground(fallbackBackground);
    }

    void activateFallback() {
        ArrayList<View> children = new ArrayList<>();
        if (content != null) {
            for (int i = 0; i < content.getChildCount(); i++) {
                children.add(content.getChildAt(i));
            }
            content.removeAllViews();
            super.removeView(content);
        }
        if (honorBackend != null) {
            try {
                honorBackend.dispose();
            } catch (RuntimeException | LinkageError ignored) {
            }
            honorBackend = null;
        }
        createFallbackContent();
        for (View child : children) {
            content.addView(child, child.getLayoutParams());
        }
        super.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void applyHonorEffect() {
        if (honorBackend == null) {
            return;
        }
        try {
            honorBackend.apply(mode, scene, widgetSize, radiusPx);
            setBackground(null);
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Unable to apply Honor glass effect", error);
            activateFallback();
        }
    }

    public void setGlassMode(GlassMode newMode) {
        if (mode == newMode && honorBackend != null) {
            return;
        }
        mode = newMode;
        applyHonorEffect();
    }

    public GlassMode getGlassMode() {
        return mode;
    }

    public void setStyle(GlassScene newScene, GlassWidgetSize newSize) {
        scene = newScene;
        widgetSize = newSize;
        applyHonorEffect();
    }

    public void setCornerRadiusDp(int radiusDp) {
        radiusPx = Math.round(radiusDp * getResources().getDisplayMetrics().density);
        fallbackBackground.setCornerRadius(radiusPx);
        applyHonorEffect();
    }

    public boolean isHonorGlassActive() {
        return honorBackend != null;
    }

    public void setOnBackgroundChangedListener(OnBackgroundChangedListener listener) {
        backgroundListener = listener;
        updateReceiverRegistration();
    }

    void dispatchBackgroundChanged(boolean darkBackground) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyBackgroundChanged(darkBackground);
        } else {
            post(() -> notifyBackgroundChanged(darkBackground));
        }
    }

    private void notifyBackgroundChanged(boolean darkBackground) {
        if (attached && backgroundListener != null) {
            backgroundListener.onBackgroundChanged(darkBackground);
        }
    }

    private void updateReceiverRegistration() {
        if (honorBackend != null) {
            honorBackend.setReceiverEnabled(attached && backgroundListener != null);
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (content != null && child != content) {
            content.addView(child, index, params);
        } else {
            super.addView(child, index, params);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateReceiverRegistration();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        updateReceiverRegistration();
        super.onDetachedFromWindow();
    }

    public static int foregroundColor(Context context, boolean darkBackground) {
        return darkBackground ? Color.WHITE : Color.BLACK;
    }
}
