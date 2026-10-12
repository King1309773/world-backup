package com.rlcmj.worldbackup.core;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BackupCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<PublicationAttempt> activePublication =
        new AtomicReference<>();

    public void cancel() {
        cancelled.set(true);
        PublicationAttempt attempt = activePublication.get();
        if (attempt != null) attempt.requestCancellation();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void publish(Publication publication) throws java.io.IOException {
        publish(publication, () -> { });
    }

    public void publish(Publication publication, Publication rollback)
            throws java.io.IOException {
        if (publication == null || rollback == null) throw new NullPointerException();
        check();
        PublicationAttempt attempt = new PublicationAttempt();
        if (!activePublication.compareAndSet(null, attempt)) {
            throw new IllegalStateException("Concurrent publication is not supported");
        }
        boolean actionCompleted = false;
        try {
            check();
            try {
                publication.run();
            } catch (java.io.IOException | RuntimeException | Error publicationFailure) {
                if (cancelled.get()) attempt.requestCancellation();
                if (attempt.cancellationRequested()) {
                    InterruptedIOException cancelledFailure =
                        new InterruptedIOException("Backup cancelled during publication");
                    cancelledFailure.initCause(publicationFailure);
                    try {
                        rollback.run();
                    } catch (java.io.IOException | RuntimeException | Error rollbackFailure) {
                        cancelledFailure.addSuppressed(rollbackFailure);
                    }
                    throw cancelledFailure;
                }
                throw publicationFailure;
            }
            actionCompleted = true;
            if (cancelled.get()) attempt.requestCancellation();
            if (attempt.markPublished()) return;
            InterruptedIOException cancelledFailure =
                new InterruptedIOException("Backup cancelled during publication");
            try {
                rollback.run();
            } catch (java.io.IOException | RuntimeException | Error rollbackFailure) {
                cancelledFailure.addSuppressed(rollbackFailure);
            }
            throw cancelledFailure;
        } finally {
            if (!actionCompleted) attempt.requestCancellation();
            activePublication.compareAndSet(attempt, null);
        }
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

    private static final class PublicationAttempt {
        private static final int RUNNING = 0;
        private static final int CANCEL_REQUESTED = 1;
        private static final int PUBLISHED = 2;
        private final AtomicInteger state = new AtomicInteger(RUNNING);

        private void requestCancellation() {
            state.compareAndSet(RUNNING, CANCEL_REQUESTED);
        }

        private boolean markPublished() {
            return state.compareAndSet(RUNNING, PUBLISHED);
        }

        private boolean cancellationRequested() {
            return state.get() == CANCEL_REQUESTED;
        }
    }
}
