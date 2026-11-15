package com.shengqiao.app.session;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.shengqiao.app.BridgeService;
import com.shengqiao.app.PcDiscovery;

import java.lang.ref.WeakReference;
import java.util.List;

public final class BridgeViewModel extends AndroidViewModel {
    private final MutableLiveData<BridgeState> state = new MutableLiveData<>(BridgeState.unavailable());
    private final MutableLiveData<DiscoveryState> discoveryState =
            new MutableLiveData<>(DiscoveryState.idle());
    private final BridgeService.Listener serviceListener = state::setValue;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BridgeService boundService = ((BridgeService.LocalBinder) binder).getService();
            service = new WeakReference<>(boundService);
            boundService.addListener(serviceListener);
            state.setValue(boundService.getSnapshot());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearService();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            clearService();
        }
    };

    private WeakReference<BridgeService> service = new WeakReference<>(null);
    private PcDiscovery discovery;
    private Object activeOwner;
    private boolean active;
    private boolean bound;

    public BridgeViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<BridgeState> getState() {
        return state;
    }

    public LiveData<DiscoveryState> getDiscoveryState() {
        return discoveryState;
    }

    public void setActive(Object owner, boolean value) {
        if (value) {
            if (active && activeOwner == owner && bound) {
                return;
            }
            activeOwner = owner;
            active = true;
            bind();
            return;
        }
        if (activeOwner != owner) {
            return;
        }
        activeOwner = null;
        if (!active) {
            return;
        }
        active = false;
        stopDiscovery();
        unbind();
    }

    public boolean connect(String host, int port, String key) {
        Context context = getApplication();
        Intent intent = new Intent(context, BridgeService.class)
                .setAction(BridgeService.ACTION_CONNECT)
                .putExtra("host", host)
                .putExtra("port", port)
                .putExtra("key", key);
        try {
            context.startForegroundService(intent);
            if (!bound) {
                bind();
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void disconnect() {
        BridgeService current = service.get();
        if (current != null) {
            current.disconnect();
        }
    }

    public void setListening(boolean enabled) {
        BridgeService current = service.get();
        if (current != null) {
            current.setListening(enabled);
        }
    }

    public void setMicrophone(boolean enabled) {
        BridgeService currentService = service.get();
        if (currentService != null) {
            BridgeState current = state.getValue();
            boolean echo = current == null || current.echoCancel;
            currentService.setMicrophone(enabled, echo);
        }
    }

    public void setBitrate(int bitrate) {
        if (preferences().getInt("bitrate", 0) == bitrate) return;
        BridgeService current = service.get();
        if (current != null) {
            current.setBitrate(bitrate);
        } else {
            preferences().edit().putInt("bitrate", bitrate).apply();
        }
    }

    public void setLowLatency(boolean enabled) {
        BridgeService current = service.get();
        if (current != null) {
            current.setLowLatency(enabled);
        } else if (preferences().getBoolean("lowLatency", false) != enabled) {
            preferences().edit().putBoolean("lowLatency", enabled).apply();
        }
    }

    public void setVolumePercent(int percent) {
        BridgeService current = service.get();
        if (current != null) {
            current.setVolume(percent / 100.0f);
        }
    }

    public void setTargetMs(int milliseconds) {
        BridgeService current = service.get();
        if (current != null && current.getSnapshot().targetMs != milliseconds) {
            current.setTargetMs(milliseconds);
        }
    }

    public void setEchoCancel(boolean enabled) {
        BridgeService current = service.get();
        if (current != null && current.getSnapshot().echoCancel != enabled) {
            current.setEchoCancel(enabled);
        }
    }

    public void setDenoise(String mode) {
        BridgeService current = service.get();
        if (current != null && !current.getSnapshot().denoise.equals(mode)) {
            current.setDenoise(mode);
        }
    }

    public void startDiscovery() {
        if (!active) {
            return;
        }
        if (discovery == null) {
            discovery = new PcDiscovery(getApplication(), this::publishDiscovery);
        }
        discovery.start();
    }

    public void stopDiscovery() {
        if (discovery != null) {
            discovery.stop();
        }
    }

    public String getSavedHost() {
        return preferences().getString("host", "");
    }

    public int getSavedPort() {
        return preferences().getInt("port", 23333);
    }

    public String getSavedKey() {
        return preferences().getString("key", "");
    }

    public String getSavedPcName() {
        return preferences().getString("pcName", "");
    }

    private SharedPreferences preferences() {
        return getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private void publishDiscovery(List<PcDiscovery.Host> hosts, boolean discovering, String error) {
        discoveryState.setValue(new DiscoveryState(hosts, discovering, error));
    }

    private void bind() {
        if (bound || !active) {
            return;
        }
        Context context = getApplication();
        bound = context.bindService(new Intent(context, BridgeService.class), connection,
                Context.BIND_AUTO_CREATE);
        if (!bound) {
            state.setValue(BridgeState.unavailable());
        }
    }

    private void unbind() {
        clearService();
        if (!bound) {
            return;
        }
        bound = false;
        try {
            getApplication().unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void clearService() {
        BridgeService current = service.get();
        if (current != null) {
            current.removeListener(serviceListener);
        }
        service.clear();
        state.setValue(BridgeState.unavailable());
    }

    @Override
    protected void onCleared() {
        activeOwner = null;
        active = false;
        stopDiscovery();
        unbind();
        super.onCleared();
    }
}
