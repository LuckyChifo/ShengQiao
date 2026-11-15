package com.shengqiao.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PcDiscovery {
    public static final class Host {
        public final String name;
        public final String address;
        public final int port;

        Host(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }

        @Override
        public String toString() {
            return name + " · " + address;
        }
    }

    public interface Listener {
        void onChanged(List<Host> hosts, boolean discovering, String error);
    }

    private final Context context;
    private final NsdManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session current;

    private final class Session implements NsdManager.DiscoveryListener, NsdManager.ResolveListener {
        private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
        private final Set<String> names = new HashSet<>();
        private final ArrayList<Host> hosts = new ArrayList<>();
        private boolean resolving;
        private String error = "";

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            main.post(() -> startFailed(this));
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            main.post(() -> {
                if (current == this) {
                    publish(this, true);
                }
            });
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            main.post(() -> {
                if (current == this) {
                    current = null;
                    publish(this, false);
                }
            });
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            main.post(() -> serviceFound(this, serviceInfo));
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            main.post(() -> serviceLost(this, serviceInfo));
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            main.post(() -> resolveFailed(this, serviceInfo));
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            main.post(() -> serviceResolved(this, serviceInfo));
        }
    }

    public PcDiscovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void start() {
        if (current != null) {
            return;
        }
        Session session = new Session();
        current = session;
        try {
            manager.discoverServices("_shengqiao._tcp.", NsdManager.PROTOCOL_DNS_SD, session);
        } catch (SecurityException e) {
            current = null;
            session.error = context.getString(R.string.discovery_error_permission);
        } catch (RuntimeException e) {
            current = null;
            session.error = context.getString(R.string.discovery_error_start);
        }
        publish(session, current == session);
    }

    public void stop() {
        Session session = current;
        current = null;
        if (session != null) {
            session.pending.clear();
            session.names.clear();
            session.hosts.clear();
            try {
                manager.stopServiceDiscovery(session);
            } catch (RuntimeException ignored) {
            }
        }
        discoveryStateCleared();
    }

    private void startFailed(Session session) {
        if (current != session) {
            return;
        }
        current = null;
        session.error = context.getString(R.string.discovery_error_start);
        publish(session, false);
    }

    private void serviceFound(Session session, NsdServiceInfo serviceInfo) {
        if (current != session || !serviceInfo.getServiceName().contains("声桥")
                || !session.names.add(serviceInfo.getServiceName())) {
            return;
        }
        session.pending.add(serviceInfo);
        resolveNext(session);
    }

    private void serviceLost(Session session, NsdServiceInfo serviceInfo) {
        if (current != session) {
            return;
        }
        String name = serviceInfo.getServiceName();
        session.names.remove(name);
        session.pending.removeIf(service -> name.equals(service.getServiceName()));
        session.hosts.removeIf(host -> host.name.equals(name));
        publish(session, true);
    }

    private void resolveFailed(Session session, NsdServiceInfo serviceInfo) {
        if (current != session) {
            return;
        }
        session.resolving = false;
        session.names.remove(serviceInfo.getServiceName());
        session.error = context.getString(R.string.discovery_error_resolve,
                serviceInfo.getServiceName());
        resolveNext(session);
        publish(session, true);
    }

    private void serviceResolved(Session session, NsdServiceInfo serviceInfo) {
        if (current != session) {
            return;
        }
        session.resolving = false;
        InetAddress address = serviceInfo.getHost();
        if (address != null) {
            session.hosts.removeIf(host -> host.name.equals(serviceInfo.getServiceName()));
            session.hosts.add(new Host(serviceInfo.getServiceName(), address.getHostAddress(),
                    serviceInfo.getPort()));
            session.error = "";
        }
        resolveNext(session);
        publish(session, true);
    }

    private void resolveNext(Session session) {
        if (current != session || session.resolving || session.pending.isEmpty()) {
            return;
        }
        session.resolving = true;
        try {
            manager.resolveService(session.pending.removeFirst(), session);
        } catch (RuntimeException e) {
            session.resolving = false;
            session.error = context.getString(R.string.discovery_error_resolve_generic);
            resolveNext(session);
        }
    }

    private void publish(Session session, boolean discovering) {
        listener.onChanged(new ArrayList<>(session.hosts), discovering, session.error);
    }

    private void discoveryStateCleared() {
        listener.onChanged(new ArrayList<>(), false, "");
    }
}
