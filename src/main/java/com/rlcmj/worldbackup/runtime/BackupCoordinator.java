package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.config.ConfigSnapshot;
import com.rlcmj.worldbackup.config.WorldBackupConfig;
import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupResult;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.storage.BackupRepository;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BackupCoordinator {
    private static final long DEFAULT_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(30);
    private static final long DEFAULT_SHUTDOWN_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Runtime runtime;
    private final ExecutorService worker;
    private final ExecutorService stagingWorker;
    private final Watchdog watchdog;
    private final NanoClock clock;
    private final long timeoutNanos;
    private final long shutdownWaitNanos;
    private final AtomicReference<ActiveBackup> active = new AtomicReference<>();
    private final AtomicBoolean restoreActive = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Runnable> pendingClientCallbacks =
        new ConcurrentLinkedQueue<>();
    private final Object arbitration = new Object();

    public BackupCoordinator() {
        this(new MinecraftRuntime(), newDaemonExecutor("World Backup Worker"),
            new ScheduledWatchdog(), System::nanoTime, DEFAULT_TIMEOUT_NANOS,
            DEFAULT_SHUTDOWN_WAIT_NANOS);
    }

    BackupCoordinator(Runtime runtime, ExecutorService worker, Watchdog watchdog,
                      NanoClock clock, long timeoutNanos, long shutdownWaitNanos) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.stagingWorker = newDaemonExecutor("World Backup Staging");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeoutNanos = timeoutNanos;
        this.shutdownWaitNanos = shutdownWaitNanos;
    }

    public boolean request(MinecraftServer server, BackupTrigger trigger) {
        return request((Object) server, trigger);
    }

    boolean request(Object server, BackupTrigger trigger) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(trigger, "trigger");
        if (!runtime.normalBackupsEnabled(server)) {
            runtime.message("worldbackup.message.disabled");
            return false;
        }
        ActiveBackup candidate = new ActiveBackup(server, trigger, clock.nanoTime());
        synchronized (arbitration) {
            if (restoreActive.get() || !active.compareAndSet(null, candidate)) {
                runtime.message("worldbackup.message.busy");
                return false;
            }
        }
        runtime.message("worldbackup.message.start");
        try {
            candidate.watchdog = watchdog.schedule(() -> timeout(candidate), timeoutNanos);
            runtime.schedule(server, () -> begin(candidate));
        } catch (Throwable schedulingFailure) {
            candidate.preparationDone.complete(null);
            candidate.stageDone.complete(null);
            candidate.workerDone.complete(null);
            failBeforeWorker(candidate, schedulingFailure);
        }
        return true;
    }

    public boolean requestRestore(RestoreOperation operation, RestoreCallback callback) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(callback, "callback");
        synchronized (arbitration) {
            if (active.get() != null || !restoreActive.compareAndSet(false, true)) {
                runtime.message("worldbackup.message.busy");
                return false;
            }
        }
        try {
            worker.submit(() -> {
                Path restored = null;
                Throwable failure = null;
                try {
                    restored = operation.run();
                } catch (Throwable restoreFailure) {
                    failure = restoreFailure;
                }
                final Path completedPath = restored;
                final Throwable completedFailure = failure;
                Runnable completion = () -> {
                    restoreActive.set(false);
                    if (completedFailure == null) callback.succeeded(completedPath);
                    else callback.failed(completedFailure);
                };
                try {
                    runtime.scheduleClient(completion);
                } catch (Throwable schedulingFailure) {
                    pendingClientCallbacks.add(completion);
                }
            });
        } catch (Throwable submissionFailure) {
            restoreActive.set(false);
            callback.failed(submissionFailure);
            return false;
        }
        return true;
    }

    public boolean isBusy() {
        return active.get() != null || restoreActive.get();
    }

    public boolean isRestoreActive() {
        return restoreActive.get();
    }

    public void retryClientCallbacks() {
        Runnable callback;
        while ((callback = pendingClientCallbacks.poll()) != null) callback.run();
    }

    @FunctionalInterface
    public interface RestoreOperation { Path run() throws Exception; }

    public interface RestoreCallback {
        void succeeded(Path restored);
        void failed(Throwable failure);
    }

    public void onServerStopping(MinecraftServer server) {
        onServerStopping((Object) server);
    }

    void onServerStopping(Object server) {
        ActiveBackup current = active.get();
        Object snapshot = null;
        boolean ownsActive = current != null && current.server == server
            && current.shutdown.compareAndSet(false, true);
        long startedNanos = ownsActive ? current.startedNanos : clock.nanoTime();
        BackupResult exitResult = null;
        Throwable failure = null;
        boolean cleanupDeferred = false;
        try {
            if (ownsActive) {
                current.cancelWatchdog();
                current.cancelQueuedPreparation();
                cancelWorker(current);
                snapshot = current.snapshot;
                try {
                    waitForCleanup(current);
                    if (!current.worldsReleased) releaseActiveWorlds(current);
                    if (snapshot != null && current.worldsReleased) {
                        runtime.cleanupSnapshot(snapshot);
                        current.snapshot = null;
                        snapshot = null;
                    }
                } catch (TimeoutException | InterruptedException waitFailure) {
                    if (waitFailure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    failure = waitFailure;
                    cleanupDeferred = true;
                    deferCleanupAndRelease(current, snapshot);
                    if (!current.worldsReleased) {
                        try {
                            releaseActiveWorlds(current);
                        } catch (Throwable releaseFailure) {
                            failure.addSuppressed(releaseFailure);
                        }
                    }
                }
            }
            if (!cleanupDeferred && runtime.exitBackupEnabled(server)) {
                if (snapshot == null) snapshot = runtime.freeze(server);
                BackupJob job = runtime.createJob(server, snapshot, BackupTrigger.EXIT);
                exitResult = runtime.run(job, new BackupCancellation());
            }
        } catch (Throwable operationFailure) {
            failure = operationFailure;
        } finally {
            if (snapshot != null && !cleanupDeferred) {
                try {
                    runtime.unfreeze(snapshot);
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (ownsActive && !cleanupDeferred) {
                current.finished.set(true);
                active.compareAndSet(current, null);
            }
        }
        if (failure != null) {
            runtime.message("worldbackup.message.failure", secondsSince(startedNanos),
                errorComponent(failure));
        } else if (exitResult != null && exitResult.isSuccess()) {
            runtime.message("worldbackup.message.success", secondsSince(startedNanos));
        } else if (exitResult != null) {
            runtime.message("worldbackup.message.failure", secondsSince(startedNanos),
                new TextComponentTranslation(exitResult.getErrorKey()));
        }
    }

    private void releaseActiveWorlds(ActiveBackup backup) throws Exception {
        if (backup.worldsReleased) return;
        if (backup.snapshot != null) runtime.releaseWorlds(backup.snapshot);
        else if (backup.prepared != null) runtime.releasePrepared(backup.prepared);
        backup.worldsReleased = true;
    }

    boolean isActive() {
        return active.get() != null;
    }

    void retryPending(Object server) {
        ActiveBackup current = active.get();
        if (current == null || current.server != server || !current.finishPending.get()) return;
        dispatchFinish(current);
    }

    private void begin(ActiveBackup backup) {
        if (!backup.preparationState.compareAndSet(ActiveBackup.PREPARATION_QUEUED,
                ActiveBackup.PREPARATION_CLAIMED)) return;
        if (backup.shutdown.get() || backup.terminal.get()) {
            backup.stageDone.complete(null);
            backup.workerDone.complete(null);
            backup.preparationDone.complete(null);
            return;
        }
        boolean consumePreparationInterrupt = false;
        try {
            backup.startPreparation(Thread.currentThread());
            try {
                backup.prepared = runtime.prepareSnapshot(backup.server,
                    backup.cancellation);
            } finally {
                consumePreparationInterrupt =
                    backup.finishPreparation(Thread.currentThread());
            }
            backup.stageTask = stagingWorker.submit(() -> stageSnapshot(backup));
            backup.snapshot = awaitStaging(backup);
            if (backup.terminal.get()) {
                releasePreparedWorlds(backup);
                backup.workerDone.complete(null);
                return;
            }
            backup.job = runtime.createJob(backup.server, backup.snapshot, backup.trigger);
            if (backup.terminal.get()) {
                backup.workerDone.complete(null);
                return;
            }
            runtime.releaseWorlds(backup.snapshot);
            backup.worldsReleased = true;
            if (backup.terminal.get()) {
                backup.workerDone.complete(null);
                return;
            }
            backup.workerTask = worker.submit(() -> runWorker(backup));
        } catch (Throwable failure) {
            if (backup.stageTask == null) backup.stageDone.complete(null);
            releasePreparedWorlds(backup, failure);
            if (backup.workerTask == null) backup.workerDone.complete(null);
            if (!backup.terminal.get()) failBeforeWorker(backup, failure);
        } finally {
            if (consumePreparationInterrupt) {
                // Preparation rollback has completed on this server thread. Consume only
                // the interrupt attributed to our watchdog before returning to its loop.
                // Java has one interrupt bit and cannot distinguish an exactly concurrent
                // external interrupt; the synchronized handshake prevents our watchdog from
                // delivering a late interrupt after the preparation cleanup boundary.
                Thread.interrupted();
            }
            backup.preparationDone.complete(null);
        }
    }

    private void stageSnapshot(ActiveBackup backup) {
        Object staged = null;
        backup.stageThread = Thread.currentThread();
        try {
            staged = runtime.stageSnapshot(backup.prepared, backup.cancellation);
            if (!backup.stageResult.complete(staged)) {
                runtime.cleanupSnapshot(staged);
            }
        } catch (Throwable failure) {
            backup.stageResult.completeExceptionally(failure);
            if (staged != null) {
                try {
                    runtime.cleanupSnapshot(staged);
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        } finally {
            backup.stageThread = null;
            backup.stageDone.complete(null);
        }
    }

    private Object awaitStaging(ActiveBackup backup) throws Exception {
        try {
            return backup.stageResult.get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (CancellationException failure) {
            throw new InterruptedException("Snapshot staging cancelled");
        }
    }

    private void releasePreparedWorlds(ActiveBackup backup) throws Exception {
        if (backup.prepared == null || backup.worldsReleased) return;
        runtime.releasePrepared(backup.prepared);
        backup.worldsReleased = true;
    }

    private void releasePreparedWorlds(ActiveBackup backup, Throwable primary) {
        try {
            releasePreparedWorlds(backup);
        } catch (Throwable releaseFailure) {
            primary.addSuppressed(releaseFailure);
        }
    }

    private void runWorker(ActiveBackup backup) {
        if (!backup.workerState.compareAndSet(ActiveBackup.WORKER_PENDING,
                ActiveBackup.WORKER_RUNNING)) {
            backup.workerDone.complete(null);
            return;
        }
        try {
            BackupResult result = runtime.run(backup.job, backup.cancellation);
            backup.result.complete(result);
            if (!backup.shutdown.get() && backup.terminal.compareAndSet(false, true)) {
                scheduleFinish(backup, result, null, false);
            }
        } catch (Throwable failure) {
            backup.result.completeExceptionally(failure);
            if (!backup.shutdown.get() && backup.terminal.compareAndSet(false, true)) {
                scheduleFinish(backup, null, failure, false);
            }
        } finally {
            backup.workerState.set(ActiveBackup.WORKER_DONE);
            backup.workerDone.complete(null);
        }
    }

    private void timeout(ActiveBackup backup) {
        if (backup.shutdown.get() || !backup.terminal.compareAndSet(false, true)) return;
        backup.stageResult.completeExceptionally(new TimeoutException("Snapshot timed out"));
        cancelWorker(backup);
        backup.preparationDone.whenComplete((prepared, preparationFailure) -> {
            if (backup.workerTask == null) backup.workerDone.complete(null);
            CompletableFuture.allOf(backup.stageDone, backup.workerDone)
                .whenComplete((ignored, cleanupFailure) ->
                    scheduleFinish(backup, null, null, true));
        });
    }

    private void cancelWorker(ActiveBackup backup) {
        backup.cancellation.cancel();
        boolean cancelledBeforeStart = backup.workerState.compareAndSet(
            ActiveBackup.WORKER_PENDING, ActiveBackup.WORKER_CANCELLED);
        Future<?> task = backup.workerTask;
        if (task != null) task.cancel(true);
        Thread staging = backup.stageThread;
        if (staging != null) staging.interrupt();
        backup.interruptPreparation();
        if (cancelledBeforeStart && task != null) {
            backup.workerDone.complete(null);
        }
    }

    private void scheduleFinish(ActiveBackup backup, BackupResult result,
                                Throwable failure, boolean timedOut) {
        backup.cancelWatchdog();
        backup.completion = new Completion(result, failure, timedOut);
        backup.finishPending.set(true);
        dispatchFinish(backup);
    }

    private void dispatchFinish(ActiveBackup backup) {
        if (!backup.finishPending.compareAndSet(true, false)) return;
        Completion completion = backup.completion;
        try {
            runtime.schedule(backup.server,
                () -> finish(backup, completion.result, completion.failure,
                    completion.timedOut));
        } catch (Throwable schedulingFailure) {
            if (completion.failure == null) completion.failure = schedulingFailure;
            backup.finishPending.set(true);
        }
    }

    private void finish(ActiveBackup backup, BackupResult result,
                        Throwable failure, boolean timedOut) {
        if (backup.finished.get()) return;
        if (!backup.finishRunning.compareAndSet(false, true)) {
            backup.finishPending.set(true);
            return;
        }
        backup.cancelWatchdog();
        try {
            Completion completion = backup.completion;
            if (completion == null) {
                completion = new Completion(result, failure, timedOut);
                backup.completion = completion;
            }
            try {
                if (backup.snapshot != null) runtime.unfreeze(backup.snapshot);
            } catch (Throwable cleanupFailure) {
                if (completion.failure == null) completion.failure = cleanupFailure;
                backup.finishPending.set(true);
                return;
            }
            if (!backup.finished.compareAndSet(false, true)) return;
            active.compareAndSet(backup, null);
            String elapsed = secondsSince(backup.startedNanos);
            if (completion.timedOut) {
                runtime.message("worldbackup.error.timeout", elapsed);
            } else if (completion.failure != null) {
                runtime.message("worldbackup.message.failure", elapsed,
                    errorComponent(completion.failure));
            } else if (completion.result != null && completion.result.isSuccess()) {
                runtime.message("worldbackup.message.success", elapsed);
            } else {
                String key = completion.result == null ? "worldbackup.error.archive_write"
                    : completion.result.getErrorKey();
                runtime.message("worldbackup.message.failure", elapsed,
                    new TextComponentTranslation(key));
            }
        } finally {
            backup.finishRunning.set(false);
        }
    }

    private void failBeforeWorker(ActiveBackup backup, Throwable failure) {
        if (backup.terminal.compareAndSet(false, true)) {
            finish(backup, null, failure, false);
        }
    }

    private void waitForCleanup(ActiveBackup backup)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + shutdownWaitNanos;
        backup.stageDone.get(shutdownWaitNanos, TimeUnit.NANOSECONDS);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) throw new TimeoutException("Snapshot cleanup timed out");
        backup.workerDone.get(remaining, TimeUnit.NANOSECONDS);
    }

    private void deferCleanupAndRelease(ActiveBackup backup, Object snapshot) {
        CompletableFuture.allOf(backup.stageDone, backup.workerDone)
            .whenComplete((ignored, workerFailure) -> {
                try {
                    worker.submit(() -> retryDeferredCleanup(backup, snapshot));
                } catch (Throwable rejected) {
                    runtime.message("worldbackup.message.failure",
                        secondsSince(backup.startedNanos), errorComponent(rejected));
                    backup.finished.set(true);
                    active.compareAndSet(backup, null);
                }
            });
    }

    private void retryDeferredCleanup(ActiveBackup backup, Object snapshot) {
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (snapshot != null) runtime.cleanupSnapshot(snapshot);
                lastFailure = null;
                break;
            } catch (Throwable cleanupFailure) {
                lastFailure = cleanupFailure;
                if (attempt < 2) {
                    try {
                        Thread.sleep(25L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        if (lastFailure != null) {
            runtime.message("worldbackup.message.failure",
                secondsSince(backup.startedNanos), errorComponent(lastFailure));
        }
        backup.finished.set(true);
        active.compareAndSet(backup, null);
    }

    private String secondsSince(long startedNanos) {
        long elapsed = Math.max(0L, clock.nanoTime() - startedNanos);
        return String.format(Locale.ROOT, "%.1f", elapsed / 1_000_000_000.0D);
    }

    private static Object errorComponent(Throwable failure) {
        if (failure instanceof BackupException) {
            BackupException backup = (BackupException) failure;
            return new TextComponentTranslation(backup.getTranslationKey(),
                backup.getArguments());
        }
        return new TextComponentTranslation("worldbackup.error.archive_write");
    }

    private static ExecutorService newDaemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(command -> {
            Thread thread = new Thread(command, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    static final class BackupJob {
        final Path worldRoot;
        final Path repository;
        final String worldId;
        final String displayName;
        final BackupMode mode;
        final BackupTrigger trigger;
        final int maxVersions;
        final int incrementsPerBase;
        final long startedAtMillis;

        BackupJob(Path worldRoot, Path repository, String worldId, String displayName,
                  BackupMode mode, BackupTrigger trigger, int maxVersions,
                  int incrementsPerBase, long startedAtMillis) {
            this.worldRoot = worldRoot;
            this.repository = repository;
            this.worldId = worldId;
            this.displayName = displayName;
            this.mode = mode;
            this.trigger = trigger;
            this.maxVersions = maxVersions;
            this.incrementsPerBase = incrementsPerBase;
            this.startedAtMillis = startedAtMillis;
        }
    }

    interface Runtime {
        boolean normalBackupsEnabled(Object server);
        boolean exitBackupEnabled(Object server);
        Object freeze(Object server) throws Exception;
        default Object prepareSnapshot(Object server) throws Exception {
            return freeze(server);
        }
        default Object prepareSnapshot(Object server,
                                       BackupCancellation cancellation) throws Exception {
            cancellation.check();
            Object prepared = prepareSnapshot(server);
            cancellation.check();
            return prepared;
        }
        default Object stageSnapshot(Object prepared,
                                     BackupCancellation cancellation) throws Exception {
            cancellation.check();
            return prepared;
        }
        default void releasePrepared(Object prepared) throws Exception {
            releaseWorlds(prepared);
        }
        void unfreeze(Object snapshot) throws Exception;
        default void releaseWorlds(Object snapshot) throws Exception {
            unfreeze(snapshot);
        }
        default void cleanupSnapshot(Object snapshot) throws Exception { }
        BackupJob createJob(Object server, Object snapshot, BackupTrigger trigger)
            throws Exception;
        BackupResult run(BackupJob job, BackupCancellation cancellation) throws Exception;
        void schedule(Object server, Runnable command);
        default void scheduleClient(Runnable command) { command.run(); }
        void message(String key, Object... arguments);
    }

    interface NanoClock { long nanoTime(); }
    interface Cancellable { void cancel(); }
    interface Watchdog { Cancellable schedule(Runnable command, long delayNanos); }

    private static final class ActiveBackup {
        private static final int WORKER_PENDING = 0;
        private static final int WORKER_RUNNING = 1;
        private static final int WORKER_CANCELLED = 2;
        private static final int WORKER_DONE = 3;
        private static final int PREPARATION_QUEUED = 0;
        private static final int PREPARATION_CLAIMED = 1;
        private static final int PREPARATION_CANCELLED = 2;

        private final Object server;
        private final BackupTrigger trigger;
        private final long startedNanos;
        private final BackupCancellation cancellation = new BackupCancellation();
        private final CompletableFuture<BackupResult> result = new CompletableFuture<>();
        private final CompletableFuture<Void> preparationDone = new CompletableFuture<>();
        private final CompletableFuture<Object> stageResult = new CompletableFuture<>();
        private final CompletableFuture<Void> stageDone = new CompletableFuture<>();
        private final CompletableFuture<Void> workerDone = new CompletableFuture<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicInteger workerState = new AtomicInteger(WORKER_PENDING);
        private final AtomicInteger preparationState =
            new AtomicInteger(PREPARATION_QUEUED);
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean finishRunning = new AtomicBoolean();
        private final AtomicBoolean finishPending = new AtomicBoolean();
        private volatile Object snapshot;
        private volatile Object prepared;
        private volatile BackupJob job;
        private volatile Future<?> workerTask;
        private volatile Future<?> stageTask;
        private Thread preparationThread;
        private boolean preparationInterruptedOnEntry;
        private boolean preparationInterruptOwned;
        private volatile Thread stageThread;
        private volatile Cancellable watchdog;
        private volatile Completion completion;
        private volatile boolean worldsReleased;

        private boolean cancelQueuedPreparation() {
            if (!preparationState.compareAndSet(PREPARATION_QUEUED,
                    PREPARATION_CANCELLED)) return false;
            preparationDone.complete(null);
            stageDone.complete(null);
            workerDone.complete(null);
            return true;
        }

        private synchronized void startPreparation(Thread thread) {
            preparationThread = thread;
            preparationInterruptedOnEntry = thread.isInterrupted();
            preparationInterruptOwned = false;
        }

        private synchronized void interruptPreparation() {
            Thread thread = preparationThread;
            if (thread == null) return;
            preparationInterruptOwned = true;
            thread.interrupt();
        }

        private synchronized boolean finishPreparation(Thread thread) {
            if (preparationThread != thread) return false;
            preparationThread = null;
            boolean consume = preparationInterruptOwned
                && !preparationInterruptedOnEntry;
            preparationInterruptOwned = false;
            preparationInterruptedOnEntry = false;
            return consume;
        }

        private ActiveBackup(Object server, BackupTrigger trigger, long startedNanos) {
            this.server = server;
            this.trigger = trigger;
            this.startedNanos = startedNanos;
        }

        private void cancelWatchdog() {
            Cancellable current = watchdog;
            if (current != null) current.cancel();
        }
    }

    private static final class Completion {
        private final BackupResult result;
        private volatile Throwable failure;
        private final boolean timedOut;

        private Completion(BackupResult result, Throwable failure, boolean timedOut) {
            this.result = result;
            this.failure = failure;
            this.timedOut = timedOut;
        }
    }

    private static final class ScheduledWatchdog implements Watchdog {
        private final ScheduledExecutorService scheduler = newWatchdogExecutor();

        @Override public Cancellable schedule(Runnable command, long delayNanos) {
            ScheduledFuture<?> future = scheduler.schedule(command, delayNanos,
                TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        }
    }

    static ScheduledThreadPoolExecutor newWatchdogExecutor() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
            command -> {
                Thread thread = new Thread(command, "World Backup Watchdog");
                thread.setDaemon(true);
                return thread;
            });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static final class MinecraftRuntime implements Runtime {
        private final WorldSnapshotController snapshots = new WorldSnapshotController(
            Minecraft.getMinecraft().gameDir.toPath());
        private final BackupRepository repository = new BackupRepository();

        @Override public boolean normalBackupsEnabled(Object server) {
            return WorldBackupConfig.enabled;
        }
        @Override public boolean exitBackupEnabled(Object server) {
            return WorldBackupConfig.enabled && WorldBackupConfig.exitEnabled;
        }
        @Override public Object freeze(Object server) throws Exception {
            return snapshots.freeze((MinecraftServer) server);
        }
        @Override public Object prepareSnapshot(Object server,
                                                BackupCancellation cancellation)
                throws Exception {
            return snapshots.prepare((MinecraftServer) server, cancellation);
        }
        @Override public Object stageSnapshot(Object prepared,
                                              BackupCancellation cancellation)
                throws Exception {
            return snapshots.stage((WorldSnapshotController.Prepared) prepared,
                cancellation);
        }
        @Override public void releasePrepared(Object prepared) throws Exception {
            snapshots.releaseWorlds((WorldSnapshotController.Prepared) prepared);
        }
        @Override public void unfreeze(Object snapshot) throws Exception {
            snapshots.unfreeze((WorldSnapshotController.Handle) snapshot);
        }
        @Override public void releaseWorlds(Object snapshot) throws Exception {
            snapshots.releaseWorlds((WorldSnapshotController.Handle) snapshot);
        }
        @Override public void cleanupSnapshot(Object snapshot) throws Exception {
            snapshots.cleanup((WorldSnapshotController.Handle) snapshot);
        }
        @Override public BackupJob createJob(Object server, Object snapshot,
                                             BackupTrigger trigger) {
            MinecraftServer minecraftServer = (MinecraftServer) server;
            WorldSnapshotController.Handle handle =
                (WorldSnapshotController.Handle) snapshot;
            Path worldRoot = handle.getWorldDirectory();
            Path sourceWorld = handle.getSourceWorldDirectory();
            ConfigSnapshot config = ConfigSnapshot.capture(
                Minecraft.getMinecraft().gameDir.toPath(), sourceWorld);
            String worldId = sourceWorld.getFileName().toString();
            return new BackupJob(worldRoot, config.getBackupRoot().resolve(worldId),
                worldId, minecraftServer.getWorldName(), config.getMode(), trigger,
                config.getMaxVersions(), config.getIncrementsPerBase(),
                System.currentTimeMillis());
        }
        @Override public BackupResult run(BackupJob job, BackupCancellation cancellation)
                throws Exception {
            return repository.createBackup(job.worldRoot, job.repository, job.worldId,
                job.displayName, job.mode, job.trigger, job.maxVersions,
                job.incrementsPerBase, job.startedAtMillis, cancellation);
        }
        @Override public void schedule(Object server, Runnable command) {
            ((MinecraftServer) server).addScheduledTask(command);
        }
        @Override public void scheduleClient(Runnable command) {
            Minecraft.getMinecraft().addScheduledTask(command);
        }
        @Override public void message(String key, Object... arguments) {
            ClientMessages.send(key, arguments);
        }
    }
}
