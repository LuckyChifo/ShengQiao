package com.shengqiao.app.ui.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class BackgroundSettingsTest {
    @Test
    public void rgbColorIsOpaque() {
        assertEquals(0xFF12AB34, BackgroundSettings.parseColor("#12AB34"));
        assertEquals(0xFF12AB34, BackgroundSettings.parseColor("12AB34"));
    }

    @Test
    public void argbColorPreservesAlpha() {
        assertEquals(0x8012AB34, BackgroundSettings.parseColor("#8012AB34"));
    }

    @Test
    public void invalidColorIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BackgroundSettings.parseColor("#1234"));
        assertThrows(IllegalArgumentException.class,
                () -> BackgroundSettings.parseColor("#GG0000"));
        assertThrows(IllegalArgumentException.class,
                () -> BackgroundSettings.parseColor(null));
    }
}
