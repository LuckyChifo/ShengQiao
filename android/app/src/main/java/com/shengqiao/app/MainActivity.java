package com.shengqiao.app;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.shengqiao.app.databinding.ActivityMainBinding;
import com.shengqiao.app.session.BridgeViewModel;
import com.shengqiao.app.ui.devices.DevicesFragment;
import com.shengqiao.app.ui.glass.GlassLayout;
import com.shengqiao.app.ui.glass.GlassSettings;
import com.shengqiao.app.ui.home.HomeFragment;
import com.shengqiao.app.ui.settings.SettingsFragment;

public final class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private BridgeViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedTheme();
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        refreshBackground();
        configureContentInsets();
        binding.navigationGlass.setCornerRadiusDp(28);
        binding.navigationGlass.setOnBackgroundChangedListener(this::updateNavigationForeground);
        refreshGlassMode();
        viewModel = new ViewModelProvider(this).get(BridgeViewModel.class);
        configureSystemBars();
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            showPage(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            showPage(R.id.nav_home);
        }
    }

    public void showDevices() {
        binding.bottomNavigation.setSelectedItemId(R.id.nav_devices);
    }

    public void refreshGlassMode() {
        if (binding != null) {
            binding.navigationGlass.setGlassMode(GlassSettings.getMode(this));
        }
    }

    public void refreshBackground() {
        if (binding != null) {
            binding.appBackground.reload();
        }
    }

    private void showPage(int itemId) {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.page_container);
        if (current != null && itemId == binding.bottomNavigation.getSelectedItemId()
                && matches(current, itemId)) {
            return;
        }
        Fragment page;
        if (itemId == R.id.nav_devices) {
            page = new DevicesFragment();
        } else if (itemId == R.id.nav_settings) {
            page = new SettingsFragment();
        } else {
            page = new HomeFragment();
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.page_container, page)
                .commit();
    }

    private boolean matches(Fragment fragment, int itemId) {
        return itemId == R.id.nav_home && fragment instanceof HomeFragment
                || itemId == R.id.nav_devices && fragment instanceof DevicesFragment
                || itemId == R.id.nav_settings && fragment instanceof SettingsFragment;
    }

    private void applySavedTheme() {
        SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        int mode = preferences.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    private void updateNavigationForeground(boolean darkBackground) {
        if (binding == null) {
            return;
        }
        int foreground = GlassLayout.foregroundColor(this, darkBackground);
        ColorStateList tint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{getColor(R.color.sq_primary), foreground});
        binding.bottomNavigation.setItemIconTintList(tint);
        binding.bottomNavigation.setItemTextColor(tint);
    }

    private void configureContentInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.pageContainer, (view, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            view.setPadding(view.getPaddingLeft(), top, view.getPaddingRight(),
                    view.getPaddingBottom());
            return insets;
        });
    }

    private void configureSystemBars() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(),
                binding.getRoot());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshGlassMode();
        refreshBackground();
        viewModel.setActive(this, true);
    }

    @Override
    protected void onStop() {
        viewModel.setActive(this, false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
