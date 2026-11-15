package com.shengqiao.app.ui.home;

import android.Manifest;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.shengqiao.app.MainActivity;
import com.shengqiao.app.R;
import com.shengqiao.app.databinding.DialogVolumeInputBinding;
import com.shengqiao.app.databinding.FragmentHomeBinding;
import com.shengqiao.app.session.BridgeState;
import com.shengqiao.app.session.BridgeViewModel;
import com.shengqiao.app.ui.common.GlassPanelDialog;
import com.shengqiao.app.ui.common.SessionUi;
import com.shengqiao.app.ui.glass.GlassLayout;
import com.shengqiao.app.ui.glass.GlassSettings;

public final class HomeFragment extends Fragment {
    private final ActivityResultLauncher<String> microphonePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::onMicrophonePermission);
    private FragmentHomeBinding binding;
    private BridgeViewModel viewModel;
    private BridgeState currentState = BridgeState.unavailable();
    private boolean rendering;
    private boolean pendingMicrophone;
    private long pendingGeneration;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ViewCompat.setAccessibilityHeading(binding.pageTitle, true);
        viewModel = new ViewModelProvider(requireActivity()).get(BridgeViewModel.class);
        binding.listenInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.home_listen, R.string.home_listen_description));
        binding.microphoneInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.home_microphone,
                        R.string.home_microphone_description));
        binding.volumeInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.home_volume, R.string.home_volume_description));
        binding.levelInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.home_level_title, R.string.home_level_description));
        binding.connectionInfo.setOnClickListener(v ->
                GlassPanelDialog.showMessage(this, R.string.home_diagnostics_title,
                        R.string.home_diagnostics_description));
        binding.connectionGlass.setCornerRadiusDp(26);
        binding.connectionGlass.setGlassMode(GlassSettings.getMode(requireContext()));
        binding.connectionButton.setIconTint(ColorStateList.valueOf(requireContext().getColor(R.color.sq_text)));
        binding.connectionGlass.setOnBackgroundChangedListener(dark -> {
            int foreground = GlassLayout.foregroundColor(requireContext(), dark);
            binding.connectionButton.setTextColor(foreground);
            binding.connectionButton.setIconTint(ColorStateList.valueOf(foreground));
        });
        binding.connectionButton.setOnClickListener(v -> ((MainActivity) requireActivity()).showDevices());
        binding.listenSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) {
                viewModel.setListening(checked);
            }
        });
        binding.microphoneSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) {
                requestMicrophone(checked);
            }
        });
        binding.volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (binding != null) {
                    binding.volumeLabel.setText(getString(R.string.home_volume_format, progress));
                }
                if (fromUser) {
                    viewModel.setVolumePercent(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        binding.volumeEditButton.setOnClickListener(v -> showVolumeInput());
        binding.detailsButton.setOnClickListener(v -> showDetails());
        binding.disconnectButton.setOnClickListener(v -> viewModel.disconnect());
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
    }

    private void requestMicrophone(boolean enabled) {
        if (!enabled) {
            pendingMicrophone = false;
            viewModel.setMicrophone(false);
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            pendingMicrophone = false;
            viewModel.setMicrophone(true);
            return;
        }
        pendingGeneration = currentState.generation;
        pendingMicrophone = true;
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void onMicrophonePermission(boolean granted) {
        boolean shouldStart = pendingMicrophone;
        pendingMicrophone = false;
        if (!granted) {
            if (binding != null) {
                Toast.makeText(requireContext(), R.string.home_mic_permission_denied,
                        Toast.LENGTH_LONG).show();
                render(currentState);
            }
            return;
        }
        BridgeState state = viewModel.getState().getValue();
        if (shouldStart && state != null && state.isConnected()
                && state.generation == pendingGeneration) {
            viewModel.setMicrophone(true);
        }
    }

    private void render(BridgeState state) {
        if (binding == null || state == null) {
            return;
        }
        currentState = state;
        if (state.microphone) {
            pendingMicrophone = false;
        }
        rendering = true;
        try {
            binding.connectionButton.setText(SessionUi.statusText(requireContext(), state));
            binding.listenSwitch.setEnabled(state.isConnected());
            binding.microphoneSwitch.setEnabled(state.isConnected());
            binding.listenSwitch.setChecked(state.listening);
            binding.microphoneSwitch.setChecked(state.microphone);
            if (state.listening && state.isConnected() && state.preferLow) {
                binding.listenStatus.setText(state.activeLow ? R.string.latency_short_low
                        : state.fallbackReason.isEmpty() ? R.string.home_listen_on : R.string.latency_short_fallback);
            } else {
                binding.listenStatus.setText(state.listening ? R.string.home_listen_on : R.string.home_listen_off);
            }
            binding.listenStatus.setTextColor(ContextCompat.getColor(requireContext(),
                    state.listening ? R.color.sq_success : R.color.sq_text_secondary));
            binding.microphoneStatus.setText(SessionUi.microphoneText(requireContext(), state));
            binding.volumeSeek.setEnabled(state.serviceAvailable);
            binding.volumeEditButton.setEnabled(state.serviceAvailable);
            if (binding.volumeSeek.getProgress() != state.volumePercent) {
                binding.volumeSeek.setProgress(state.volumePercent);
            }
            binding.volumeLabel.setText(getString(R.string.home_volume_format, state.volumePercent));
            binding.detailsButton.setEnabled(state.isConnected());
            binding.disconnectButton.setVisibility(state.serviceAvailable
                    && state.connection != BridgeState.Connection.IDLE ? View.VISIBLE : View.GONE);
            if (state.isConnected()) {
                String rtt = state.rttMs < 0 ? getString(R.string.not_measured)
                        : getString(R.string.milliseconds, state.rttMs);
                binding.connectionSummary.setText(getString(R.string.home_summary_format,
                        getString(R.string.home_details_rtt, rtt),
                        getString(R.string.home_details_buffer, state.bufferedMs,
                                state.actualTargetMs)));
            } else {
                binding.connectionSummary.setText(SessionUi.statusText(requireContext(), state));
            }
        } finally {
            rendering = false;
        }
    }

    private void showVolumeInput() {
        DialogVolumeInputBinding fields = DialogVolumeInputBinding.inflate(getLayoutInflater());
        fields.volumeInput.setText(getString(R.string.home_volume_input_value,
                currentState.volumePercent));
        GlassPanelDialog dialog = new GlassPanelDialog(this, R.string.home_volume_input_title,
                fields.getRoot(), android.R.string.ok);
        fields.volumeInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.submit();
                return true;
            }
            return false;
        });
        dialog.setConfirmation(R.string.cancel, () -> {
            fields.volumeInputLayout.setError(null);
            String value = fields.volumeInput.getText() == null ? ""
                    : fields.volumeInput.getText().toString().trim();
            int percent;
            try {
                percent = Integer.parseInt(value);
                if (percent < 0 || percent > 200) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                fields.volumeInputLayout.setError(getString(R.string.home_volume_input_invalid));
                return false;
            }
            viewModel.setVolumePercent(percent);
            return true;
        });
        fields.volumeInput.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    private void showDetails() {
        ConnectionDetailsDialog.show(this, currentState);
    }

    @Override
    public void onDestroyView() {
        pendingMicrophone = false;
        binding.connectionGlass.setOnBackgroundChangedListener(null);
        binding = null;
        super.onDestroyView();
    }
}
