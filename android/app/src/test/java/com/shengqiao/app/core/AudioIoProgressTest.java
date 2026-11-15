package com.shengqiao.app.core;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class AudioIoProgressTest {
    @Test
    public void shortTransfersResetDeadlineAndZeroTransfersEventuallyFail() {
        AudioIoProgress progress = new AudioIoProgress(0);
        progress.record(0, 249_000_000);
        progress.record(2, 249_000_000);
        progress.record(0, 498_000_000);
        assertThrows(IllegalStateException.class, () -> progress.record(0, 499_000_000));
    }

    @Test
    public void systemErrorsFailImmediately() {
        AudioIoProgress progress = new AudioIoProgress(0);
        assertThrows(IllegalStateException.class, () -> progress.record(-6, 1));
    }
}
