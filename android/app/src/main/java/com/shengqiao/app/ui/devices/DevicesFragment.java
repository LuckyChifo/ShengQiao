package com.shengqiao.app.ui.devices;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.shengqiao.app.PcDiscovery;
import com.shengqiao.app.R;
import com.shengqiao.app.databinding.DialogManualConnectionBinding;
import com.shengqiao.app.databinding.FragmentDevicesBinding;
import com.shengqiao.app.session.BridgeViewModel;
import com.shengqiao.app.session.DiscoveryState;
import com.shengqiao.app.ui.common.GlassPanelDialog;
import com.shengqiao.app.ui.common.SessionUi;

import java.util.Locale;

public final class DevicesFragment extends Fragment {
    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    this::onNotificationPermission);
    private FragmentDevicesBinding binding;
    private BridgeViewModel viewModel;
    private HostAdapter adapter;
    private String pendingHost;
    private int pendingPort;
    private String pendingKey;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDevicesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ViewCompat.setAccessibilityHeading(binding.pageTitle, true);
        viewModel = new ViewModelProvider(requireActivity()).get(BridgeViewModel.class);
        adapter = new HostAdapter(this::openManualDialog);
        binding.hostsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.hostsList.setAdapter(adapter);
        binding.refreshButton.setOnClickListener(v -> {
            viewModel.stopDiscovery();
            viewModel.startDiscovery();
        });
        binding.manualConnectButton.setOnClickListener(v -> openManualDialog(
                viewModel.getSavedHost(), viewModel.getSavedPort()));
        binding.recentConnectButton.setOnClickListener(v -> openManualDialog(
                viewModel.getSavedHost(), viewModel.getSavedPort()));
        viewModel.getState().observe(getViewLifecycleOwner(), state -> {
            if (binding == null || state == null) {
                return;
            }
            binding.deviceConnectionStatus.setText(SessionUi.statusText(requireContext(), state));
            binding.deviceConnectionStatus.setTextColor(SessionUi.statusColor(requireContext(), state));
            renderRecent();
        });
        viewModel.getDiscoveryState().observe(getViewLifecycleOwner(), this::renderDiscovery);
    }

    private void renderRecent() {
        String host = viewModel.getSavedHost();
        if (host.isEmpty()) {
            binding.recentTitle.setText(R.string.devices_no_recent);
            binding.recentSubtitle.setText(R.string.devices_recent_hint);
            binding.recentConnectButton.setEnabled(false);
            return;
        }
        String name = viewModel.getSavedPcName();
        binding.recentTitle.setText(name.isEmpty() ? host : name);
        binding.recentSubtitle.setText(getString(R.string.home_details_address, host,
                viewModel.getSavedPort()));
        binding.recentConnectButton.setEnabled(true);
    }

    private void renderDiscovery(DiscoveryState state) {
        if (binding == null || state == null) {
            return;
        }
        adapter.submitList(state.hosts);
        binding.discoveryProgress.setVisibility(state.discovering ? View.VISIBLE : View.GONE);
        binding.hostsEmpty.setText(state.discovering ? R.string.devices_searching
                : R.string.devices_empty);
        binding.hostsEmpty.setVisibility(state.hosts.isEmpty() ? View.VISIBLE : View.GONE);
        binding.discoveryError.setText(state.error);
        binding.discoveryError.setVisibility(state.error.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openManualDialog(PcDiscovery.Host host) {
        openManualDialog(host.address, host.port);
    }

    private void openManualDialog(String host, int port) {
        DialogManualConnectionBinding fields = DialogManualConnectionBinding.inflate(
                getLayoutInflater());
        fields.hostInput.setText(host);
        fields.portInput.setText(String.format(Locale.ROOT, "%d", port));
        fields.keyInput.setText(viewModel.getSavedKey());
        GlassPanelDialog dialog = new GlassPanelDialog(this, R.string.devices_manual_title,
                fields.getRoot(), R.string.devices_connect);
        dialog.setConfirmation(R.string.devices_cancel, () -> {
            fields.hostInputLayout.setError(null);
            fields.portInputLayout.setError(null);
            String address = fields.hostInput.getText() == null ? ""
                    : fields.hostInput.getText().toString().trim();
            if (address.isEmpty()) {
                fields.hostInputLayout.setError(getString(R.string.devices_host_required));
                fields.hostInput.requestFocus();
                return false;
            }
            int portNumber;
            try {
                String value = fields.portInput.getText() == null ? ""
                        : fields.portInput.getText().toString().trim();
                portNumber = Integer.parseInt(value);
                if (portNumber < 1 || portNumber > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                fields.portInputLayout.setError(getString(R.string.devices_port_invalid));
                fields.portInput.requestFocus();
                return false;
            }
            String key = fields.keyInput.getText() == null ? ""
                    : fields.keyInput.getText().toString().trim();
            beginConnect(address, portNumber, key);
            return true;
        });
        fields.keyInput.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            dialog.submit();
            return true;
        });
        dialog.show();
    }

    private void onNotificationPermission(boolean granted) {
        if (!granted && binding != null) {
            Toast.makeText(requireContext(), R.string.devices_notification_denied,
                    Toast.LENGTH_LONG).show();
        }
        connectPending();
    }

    private void beginConnect(String host, int port, String key) {
        pendingHost = host;
        pendingPort = port;
        pendingKey = key;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            connectPending();
        }
    }

    private void connectPending() {
        if (pendingHost == null || viewModel == null) {
            return;
        }
        String host = pendingHost;
        int port = pendingPort;
        String key = pendingKey;
        pendingHost = null;
        pendingKey = null;
        if (!viewModel.connect(host, port, key)) {
            Toast.makeText(requireContext(), R.string.devices_connect_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.startDiscovery();
    }

    @Override
    public void onPause() {
        viewModel.stopDiscovery();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        pendingHost = null;
        pendingKey = null;
        if (binding != null) {
            binding.hostsList.setAdapter(null);
        }
        binding = null;
        super.onDestroyView();
    }
}
