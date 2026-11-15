package com.shengqiao.app.ui.glass;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GlassModeTest {
    @Test
    public void storedModesRoundTrip() {
        for (GlassMode mode : GlassMode.values()) {
            assertEquals(mode, GlassMode.fromStorage(mode.storageValue));
        }
    }

    @Test
    public void unknownOrMissingModeUsesSystem() {
        assertEquals(GlassMode.SYSTEM, GlassMode.fromStorage(null));
        assertEquals(GlassMode.SYSTEM, GlassMode.fromStorage("unknown"));
    }
}
