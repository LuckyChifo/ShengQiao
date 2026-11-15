package com.shengqiao.app.session;

import com.shengqiao.app.PcDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DiscoveryState {
    public final List<PcDiscovery.Host> hosts;
    public final boolean discovering;
    public final String error;

    public DiscoveryState(List<PcDiscovery.Host> hosts, boolean discovering, String error) {
        this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
        this.discovering = discovering;
        this.error = error == null ? "" : error;
    }

    public static DiscoveryState idle() {
        return new DiscoveryState(Collections.emptyList(), false, "");
    }
}
