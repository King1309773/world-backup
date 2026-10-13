package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupCancellation;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class BackupManagerLoadController<T> {
    private final ExecutorService worker;
    private final ClientScheduler clientScheduler;
    private int generation;
    private BackupCancellation cancellation;
    private Future<?> future;
    private boolean loading;
    private boolean loaded;

    BackupManagerLoadController(ExecutorService worker, ClientScheduler clientScheduler) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clientScheduler = Objects.requireNonNull(clientScheduler, "clientScheduler");
    }

    synchronized void start(LoadOperation<T> operation, Completion<T> completion) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(completion, "completion");
        cancelCurrent();
        final int requestedGeneration = ++generation;
        final BackupCancellation requestedCancellation = new BackupCancellation();
        cancellation = requestedCancellation;
        loading = true;
        loaded = false;
        future = worker.submit(() -> {
            T value = null;
            Throwable failure = null;
            try {
                value = operation.load(requestedCancellation);
            } catch (Throwable loadFailure) {
                failure = loadFailure;
            }
            final T completedValue = value;
            final Throwable completedFailure = failure;
            try {
                clientScheduler.schedule(() -> publish(requestedGeneration,
                    completedValue, completedFailure, completion));
            } catch (RuntimeException ignored) {
                // A closed client has no safe UI thread on which to publish.
            }
        });
    }

    synchronized void cancel() {
        generation++;
        cancelCurrent();
        loading = false;
        loaded = false;
    }

    synchronized boolean isLoading() {
        return loading;
    }

    synchronized boolean isLoaded() {
        return loaded;
    }

    private void publish(int requestedGeneration, T value, Throwable failure,
                         Completion<T> completion) {
        synchronized (this) {
            if (requestedGeneration != generation) return;
            future = null;
            cancellation = null;
            loading = false;
            loaded = failure == null;
        }
        if (failure == null) completion.succeeded(value);
        else completion.failed(failure);
    }

    private void cancelCurrent() {
        if (cancellation != null) {
            cancellation.cancel();
            cancellation = null;
        }
        if (future != null) {
            future.cancel(true);
            future = null;
        }
    }

    @FunctionalInterface
    interface LoadOperation<T> {
        T load(BackupCancellation cancellation) throws Exception;
    }

    @FunctionalInterface
    interface ClientScheduler {
        void schedule(Runnable command);
    }

    interface Completion<T> {
        void succeeded(T value);
        void failed(Throwable failure);
    }
}
