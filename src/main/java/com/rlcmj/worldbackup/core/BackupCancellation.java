package com.rlcmj.worldbackup.core;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackupCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public synchronized void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public synchronized void publish(Publication publication) throws java.io.IOException {
        check();
        publication.run();
    }

    public void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Backup cancelled");
        }
    }

    @FunctionalInterface
    public interface Publication {
        void run() throws java.io.IOException;
    }
}
