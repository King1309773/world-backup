package com.rlcmj.worldbackup.core;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackupCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Backup cancelled");
        }
    }
}
