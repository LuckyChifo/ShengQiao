package com.shengqiao.app.ui.common;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.shengqiao.app.R;
import com.shengqiao.app.databinding.DialogGlassPanelBinding;
import com.shengqiao.app.ui.glass.GlassDialogBlur;
import com.shengqiao.app.ui.glass.GlassLayout;
import com.shengqiao.app.ui.glass.GlassScene;
import com.shengqiao.app.ui.glass.GlassSettings;
import com.shengqiao.app.ui.glass.GlassWidgetSize;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class GlassPanelDialog extends AppCompatDialog implements DefaultLifecycleObserver {
    private final Lifecycle ownerLifecycle;
    private final DialogGlassPanelBinding binding;
    private final List<TextView> foreground = new ArrayList<>();
    private boolean blurApplied;

    public GlassPanelDialog(Fragment owner, @StringRes int title, View content,
                            @StringRes int positiveLabel) {
        super(owner.requireContext());
        ownerLifecycle = owner.getViewLifecycleOwner().getLifecycle();
        binding = DialogGlassPanelBinding.inflate(LayoutInflater.from(owner.requireContext()));
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        binding.getRoot().setStyle(GlassScene.GENERAL, GlassWidgetSize.BIG);
        binding.getRoot().setCornerRadiusDp(28);
        binding.getRoot().setGlassMode(GlassSettings.getMode(getContext()));
        binding.panelTitle.setText(title);
        ViewCompat.setAccessibilityHeading(binding.panelTitle, true);
        ViewCompat.setAccessibilityPaneTitle(binding.getRoot(), getContext().getText(title));
        binding.panelBody.addView(content, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        binding.panelPositive.setText(positiveLabel);
        binding.panelPositive.setOnClickListener(view -> dismiss());
        binding.panelCancel.setOnClickListener(view -> dismiss());
        setCanceledOnTouchOutside(true);

        addForegroundText(binding.panelTitle);
        addForegroundText(binding.panelCancel);
        binding.getRoot().setOnBackgroundChangedListener(dark -> {
            int color = GlassLayout.foregroundColor(getContext(), dark);
            for (TextView text : foreground) {
                text.setTextColor(color);
            }
        });
        setContentView(binding.getRoot());
    }

    public static void showMessage(Fragment owner, @StringRes int title, @StringRes int message) {
        TextView text = new TextView(owner.requireContext());
        text.setTextAppearance(R.style.TextAppearance_ShengQiao_Body);
        text.setText(message);
        GlassPanelDialog dialog = new GlassPanelDialog(owner, title, text, R.string.close);
        dialog.addForegroundText(text);
        dialog.show();
    }

    public void addForegroundText(TextView text) {
        foreground.add(text);
    }

    public void setConfirmation(@StringRes int cancelLabel, BooleanSupplier confirm) {
        binding.panelCancel.setText(cancelLabel);
        binding.panelCancel.setVisibility(View.VISIBLE);
        binding.panelPositive.setOnClickListener(view -> {
            if (confirm.getAsBoolean()) {
                dismiss();
            }
        });
    }

    public void submit() {
        binding.panelPositive.performClick();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.25f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | (window.getAttributes().softInputMode
                    & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST));
        }
    }

    @Override
    public void show() {
        if (isShowing() || ownerLifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
            return;
        }
        ownerLifecycle.addObserver(this);
        super.show();
        Window window = getWindow();
        if (window != null) {
            int availableDp = getContext().getResources().getConfiguration().screenWidthDp;
            int widthDp = Math.min(520, Math.max(1, availableDp - 32));
            float density = getContext().getResources().getDisplayMetrics().density;
            window.setLayout(Math.round(widthDp * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        blurApplied = GlassDialogBlur.apply(binding.getRoot());
    }

    @Override
    public void dismiss() {
        if (blurApplied) {
            blurApplied = false;
            GlassDialogBlur.clear(binding.getRoot());
        }
        ownerLifecycle.removeObserver(this);
        super.dismiss();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        dismiss();
    }
}
