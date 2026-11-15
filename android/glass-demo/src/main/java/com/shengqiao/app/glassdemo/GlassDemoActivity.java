package com.shengqiao.app.glassdemo;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.shengqiao.app.ui.glass.GlassCapability;
import com.shengqiao.app.ui.glass.GlassDialogBlur;
import com.shengqiao.app.ui.glass.GlassLayout;
import com.shengqiao.app.ui.glass.GlassMode;
import com.shengqiao.app.ui.glass.GlassScene;
import com.shengqiao.app.ui.glass.GlassWidgetSize;

import java.util.ArrayList;
import java.util.List;

public final class GlassDemoActivity extends Activity {
    private final List<GlassLayout> glassViews = new ArrayList<>();
    private GlassMode mode = GlassMode.SYSTEM;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.addView(createScrollingBackground(), matchParent());
        setContentView(root);

        GlassLayout topPanel = new GlassLayout(this);
        topPanel.setStyle(GlassScene.GENERAL, GlassWidgetSize.BIG);
        topPanel.setCornerRadiusDp(24);
        topPanel.setPadding(dp(20), dp(16), dp(20), dp(16));
        View controls = createModeControls();
        topPanel.addView(controls);
        adaptForeground(topPanel, controls);
        glassViews.add(topPanel);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        topParams.setMargins(dp(16), dp(24), dp(16), 0);
        root.addView(topPanel, topParams);

        GlassLayout action = glassText("通透按钮", GlassWidgetSize.SMALL);
        action.setOnClickListener(view -> Toast.makeText(this,
                "按钮点击正常", Toast.LENGTH_SHORT).show());
        glassViews.add(action);
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                dp(180), dp(64), Gravity.CENTER);
        root.addView(action, actionParams);

        GlassLayout navigation = new GlassLayout(this);
        navigation.setCornerRadiusDp(24);
        View navigationContent = createNavigation();
        navigation.addView(navigationContent);
        adaptForeground(navigation, navigationContent);
        glassViews.add(navigation);
        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM);
        navigationParams.setMargins(dp(16), 0, dp(16), dp(24));
        root.addView(navigation, navigationParams);
        updateStatus();
    }

    private View createScrollingBackground() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int[] colors = {0xFFCAE9E1, 0xFF78BDAF, 0xFF315C57, 0xFFDBD2B7,
                0xFF8B7965, 0xFF263A38, 0xFFD7E8F2, 0xFF4C718B};
        for (int i = 0; i < colors.length; i++) {
            TextView section = new TextView(this);
            section.setBackgroundColor(colors[i]);
            section.setText(getString(R.string.demo_background_section, i + 1));
            section.setTextColor(i % 3 == 2 ? Color.WHITE : Color.BLACK);
            section.setTextSize(24);
            section.setGravity(Gravity.CENTER);
            content.addView(section, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        }
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View createModeControls() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("荣耀通透控件 11.0.0.134", 20);
        title.setTextColor(0xFF18211E);
        status = text("", 14);
        status.setTextColor(0xFF18211E);
        column.addView(title);
        column.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"跟随系统", "液态玻璃", "磨砂玻璃"};
        GlassMode[] modes = {GlassMode.SYSTEM, GlassMode.LIQUID, GlassMode.FROSTED};
        for (int i = 0; i < labels.length; i++) {
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextColor(0xFF18211E);
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setMinHeight(dp(52));
            GlassMode selected = modes[i];
            button.setOnClickListener(view -> setMode(selected));
            row.addView(button, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        column.addView(row);
        return column;
    }

    private void adaptForeground(GlassLayout glass, View content) {
        glass.setOnBackgroundChangedListener(dark -> {
            int color = GlassLayout.foregroundColor(this, dark);
            updateForeground(content, color);
        });
    }

    private void updateForeground(View view, int color) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                updateForeground(group.getChildAt(i), color);
            }
        }
    }

    private View createNavigation() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(navText("首页"));
        row.addView(navText("设备"));
        row.addView(navText("弹框"));
        return row;
    }

    private TextView navText(String label) {
        TextView view = text(label, 17);
        view.setTextColor(0xFF18211E);
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(clicked -> {
            if ("弹框".equals(label)) {
                showGlassDialog();
            } else {
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
            }
        });
        rowLayout(view);
        return view;
    }

    private void rowLayout(TextView view) {
        view.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private GlassLayout glassText(String label, GlassWidgetSize size) {
        GlassLayout glass = new GlassLayout(this);
        glass.setStyle(GlassScene.GENERAL, size);
        glass.setCornerRadiusDp(22);
        TextView text = text(label, 18);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(0xFF18211E);
        glass.addView(text, matchParent());
        glass.setOnBackgroundChangedListener(dark ->
                text.setTextColor(GlassLayout.foregroundColor(this, dark)));
        return glass;
    }

    private void setMode(GlassMode selected) {
        mode = selected;
        for (GlassLayout view : glassViews) {
            view.setGlassMode(mode);
        }
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) {
            return;
        }
        int sdkState = glassViews.get(0).isHonorGlassActive()
                ? R.string.demo_sdk_created : R.string.demo_sdk_fallback;
        status.setText(getString(R.string.demo_status,
                GlassCapability.getTransparencyMode(this), getString(sdkState), mode.name()));
    }

    private void showGlassDialog() {
        Dialog dialog = new Dialog(this);
        GlassLayout glass = new GlassLayout(this);
        glass.setGlassMode(mode);
        glass.setStyle(GlassScene.GENERAL, GlassWidgetSize.BIG);
        glass.setCornerRadiusDp(28);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView message = text("Dialog 跨图层通透验证\n检查背景、遮罩与关闭恢复", 18);
        Button close = new Button(this);
        close.setText("关闭");
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(view -> dialog.dismiss());
        content.addView(message);
        content.addView(close);
        glass.addView(content);
        glass.setOnBackgroundChangedListener(dark -> {
            int color = GlassLayout.foregroundColor(this, dark);
            message.setTextColor(color);
            close.setTextColor(color);
        });
        dialog.setContentView(glass);
        dialog.setOnDismissListener(ignored -> GlassDialogBlur.clear(glass));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.25f);
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        GlassDialogBlur.apply(glass);
    }

    private TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        return view;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
