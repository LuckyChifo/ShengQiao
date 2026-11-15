package com.shengqiao.app.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.shengqiao.app.MainActivity;
import com.shengqiao.app.R;
import com.shengqiao.app.databinding.FragmentSettingsBinding;
import com.shengqiao.app.session.BridgeState;
import com.shengqiao.app.session.BridgeViewModel;
import com.shengqiao.app.ui.common.GlassPanelDialog;
import com.shengqiao.app.ui.common.SessionUi;
import com.shengqiao.app.ui.glass.BackgroundSettings;
import com.shengqiao.app.ui.glass.GlassCapability;
import com.shengqiao.app.ui.glass.GlassMode;
import com.shengqiao.app.ui.glass.GlassSettings;

import java.util.function.IntConsumer;

public final class SettingsFragment extends Fragment {
    private static final int[] BUFFER_TARGETS = {40, 70, 150};
    private static final int[] BITRATES = {0, 320, 256, 128, 64};
    private static final String[] DENOISE_MODES = {"off", "light", "rnnoise", "strong"};

    private FragmentSettingsBinding binding;
    private BridgeViewModel viewModel;
    private SharedPreferences preferences;
    private boolean rendering;
    private final ActivityResultLauncher<String[]> imagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onBackgroundImageSelected);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ViewCompat.setAccessibilityHeading(binding.pageTitle, true);
        viewModel = new ViewModelProvider(requireActivity()).get(BridgeViewModel.class);
        preferences = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        binding.lowLatencyInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_low_latency,
                        R.string.settings_low_latency_description));
        binding.bufferInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_buffer,
                        R.string.settings_buffer_description));
        binding.bitrateInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_bitrate,
                        R.string.settings_bitrate_description));
        binding.denoiseInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_denoise,
                        R.string.settings_denoise_description));
        binding.echoInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_echo,
                        R.string.settings_echo_description));
        binding.themeInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_theme,
                        R.string.settings_theme_description));
        binding.materialInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_material,
                        R.string.settings_material_description));
        binding.pageBackgroundInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_page_background,
                        R.string.settings_page_background_description));
        binding.backgroundWorkInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.settings_background_title,
                        R.string.settings_background_description));
        binding.bufferChoice.setOnClickListener(v ->
                showChoices(R.string.settings_buffer, R.array.buffer_options,
                        targetPosition(viewModel.getState().getValue().targetMs),
                        position -> viewModel.setTargetMs(BUFFER_TARGETS[position])));
        binding.bitrateChoice.setOnClickListener(v ->
                showChoices(R.string.settings_bitrate, R.array.bitrate_options,
                        bitratePosition(preferences.getInt("bitrate", 0)), position -> {
                            int bitrate = BITRATES[position];
                            viewModel.setBitrate(bitrate);
                            showSelected(binding.bitrateChoice, R.array.bitrate_options, position);
                        }));
        binding.denoiseChoice.setOnClickListener(v ->
                showChoices(R.string.settings_denoise, R.array.denoise_options,
                        denoisePosition(viewModel.getState().getValue().denoise),
                        position -> viewModel.setDenoise(DENOISE_MODES[position])));
        binding.themeChoice.setOnClickListener(v ->
                showChoices(R.string.settings_theme, R.array.theme_options, selectedTheme(), position -> {
                    int mode = themeMode(position);
                    if (position != selectedTheme()) {
                        preferences.edit().putInt("theme", mode).apply();
                        showSelected(binding.themeChoice, R.array.theme_options, position);
                        AppCompatDelegate.setDefaultNightMode(mode);
                    }
                }));
        binding.materialChoice.setOnClickListener(v ->
                showChoices(R.string.settings_material, R.array.material_options,
                        materialPosition(GlassSettings.getMode(requireContext())), position -> {
                            GlassSettings.setMode(requireContext(), materialMode(position));
                            updateMaterialStatus();
                            ((MainActivity) requireActivity()).refreshGlassMode();
                        }));
        showSelected(binding.themeChoice, R.array.theme_options, selectedTheme());
        updateMaterialStatus();
        binding.lowLatencySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            viewModel.setLowLatency(checked);
            if (!viewModel.getState().getValue().serviceAvailable) render(viewModel.getState().getValue());
        });
        binding.echoSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) {
                viewModel.setEchoCancel(checked);
            }
        });
        binding.backgroundColorButton.setOnClickListener(v -> showBackgroundColorDialog());
        binding.backgroundImageButton.setOnClickListener(v ->
                imagePicker.launch(new String[]{"image/*"}));
        binding.backgroundResetButton.setOnClickListener(v -> {
            BackgroundSettings.reset(requireContext());
            refreshCustomBackground();
        });
        binding.backgroundButton.setOnClickListener(v -> allowBackground());
        updateBackgroundChoiceStatus();
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
    }

    private void showChoices(int title, int entries, int selected, IntConsumer onSelect) {
        LinearLayout options = new LinearLayout(requireContext());
        options.setOrientation(LinearLayout.VERTICAL);
        GlassPanelDialog dialog = new GlassPanelDialog(this, title, options, R.string.cancel);
        CharSequence[] labels = getResources().getTextArray(entries);
        for (int index = 0; index < labels.length; index++) {
            MaterialRadioButton row = (MaterialRadioButton) getLayoutInflater()
                    .inflate(R.layout.item_setting_choice, options, false);
            row.setText(labels[index]);
            row.setChecked(index == selected);
            int position = index;
            row.setOnClickListener(view -> {
                dialog.dismiss();
                onSelect.accept(position);
            });
            options.addView(row);
            dialog.addForegroundText(row);
        }
        dialog.show();
    }

    private void showSelected(MaterialButton button, int entries, int position) {
        CharSequence label = getResources().getTextArray(entries)[position];
        if (!android.text.TextUtils.equals(button.getText(), label)) {
            button.setText(label);
        }
    }

    private void render(BridgeState state) {
        if (binding == null || state == null) {
            return;
        }
        rendering = true;
        try {
            binding.lowLatencySwitch.setChecked(state.serviceAvailable ? state.preferLow
                    : preferences.getBoolean("lowLatency", false));
            binding.lowLatencyStatus.setText(binding.lowLatencySwitch.isChecked()
                    ? SessionUi.latencyText(requireContext(), state) : "");
            binding.lowLatencyStatus.setVisibility(binding.lowLatencySwitch.isChecked() ? View.VISIBLE : View.GONE);
            binding.bufferChoice.setEnabled(state.serviceAvailable && !state.activeLow);
            binding.denoiseChoice.setEnabled(state.isConnected());
            binding.echoSwitch.setEnabled(state.serviceAvailable);
            binding.echoSwitch.setChecked(state.echoCancel);
            showSelected(binding.bufferChoice, R.array.buffer_options, targetPosition(state.targetMs));
            int preferredBitrate = preferences.getInt("bitrate", 0);
            showSelected(binding.bitrateChoice, R.array.bitrate_options, bitratePosition(preferredBitrate));
            if (!state.isConnected()) {
                binding.bitrateStatus.setText(R.string.settings_bitrate_pcm);
            } else if (preferredBitrate != 0 && !state.opusAvailable) {
                binding.bitrateStatus.setText(R.string.settings_bitrate_unavailable);
            } else if (state.bitrate > 0) {
                binding.bitrateStatus.setText(getString(R.string.settings_bitrate_opus, state.bitrate));
            } else {
                binding.bitrateStatus.setText(R.string.settings_bitrate_pcm);
            }
            showSelected(binding.denoiseChoice, R.array.denoise_options, denoisePosition(state.denoise));
        } finally {
            rendering = false;
        }
    }

    private int targetPosition(int target) {
        if (target <= 40) {
            return 0;
        }
        return target >= 150 ? 2 : 1;
    }

    private int bitratePosition(int bitrate) {
        for (int i = 0; i < BITRATES.length; i++) {
            if (BITRATES[i] == bitrate) return i;
        }
        return 0;
    }

    private int denoisePosition(String mode) {
        for (int i = 0; i < DENOISE_MODES.length; i++) {
            if (DENOISE_MODES[i].equals(mode)) return i;
        }
        return 0;
    }

    private int materialPosition(GlassMode mode) {
        if (mode == GlassMode.LIQUID) return 1;
        return mode == GlassMode.FROSTED ? 2 : 0;
    }

    private GlassMode materialMode(int position) {
        if (position == 1) return GlassMode.LIQUID;
        return position == 2 ? GlassMode.FROSTED : GlassMode.SYSTEM;
    }

    private void updateMaterialStatus() {
        if (binding == null) {
            return;
        }
        boolean supported = GlassCapability.isSupported(requireContext());
        binding.materialChoice.setEnabled(supported);
        showSelected(binding.materialChoice, R.array.material_options,
                materialPosition(GlassSettings.getMode(requireContext())));
        binding.materialStatus.setText(supported
                ? R.string.settings_material_supported : R.string.settings_material_unsupported);
    }

    private int selectedTheme() {
        int mode = preferences.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            return 1;
        }
        return mode == AppCompatDelegate.MODE_NIGHT_YES ? 2 : 0;
    }

    private int themeMode(int position) {
        if (position == 1) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        return position == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    private void showBackgroundColorDialog() {
        BackgroundColorDialog.show(this, BackgroundSettings.getCustomColor(requireContext()),
                color -> {
                    BackgroundSettings.setColor(requireContext(), color);
                    refreshCustomBackground();
                });
    }

    private void onBackgroundImageSelected(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException error) {
            Toast.makeText(requireContext(), R.string.settings_background_image_permission_error,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        BackgroundSettings.setImage(requireContext(), uri.toString());
        refreshCustomBackground();
    }

    private void refreshCustomBackground() {
        ((MainActivity) requireActivity()).refreshBackground();
        updateBackgroundChoiceStatus();
    }

    private void updateBackgroundChoiceStatus() {
        if (binding == null) {
            return;
        }
        int type = BackgroundSettings.getType(requireContext());
        if (type == BackgroundSettings.TYPE_COLOR) {
            binding.backgroundChoiceStatus.setText(getString(
                    R.string.settings_background_current_color,
                    BackgroundSettings.getCustomColor(requireContext())));
        } else if (type == BackgroundSettings.TYPE_IMAGE) {
            binding.backgroundChoiceStatus.setText(R.string.settings_background_current_image);
        } else {
            binding.backgroundChoiceStatus.setText(R.string.settings_background_current_default);
        }
    }

    private void allowBackground() {
        PowerManager power = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
        if (power.isIgnoringBatteryOptimizations(requireContext().getPackageName())) {
            Toast.makeText(requireContext(), R.string.settings_background_already_allowed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + requireContext().getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + requireContext().getPackageName())));
        }
    }

    private void updateBackgroundStatus() {
        if (binding == null) {
            return;
        }
        PowerManager power = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
        boolean allowed = power.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        binding.backgroundStatus.setText(allowed ? R.string.settings_background_allowed
                : R.string.settings_background_request);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateBackgroundStatus();
        updateMaterialStatus();
        updateBackgroundChoiceStatus();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
