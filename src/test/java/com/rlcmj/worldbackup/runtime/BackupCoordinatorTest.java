package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupResult;
import com.rlcmj.worldbackup.core.BackupTrigger;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupCoordinatorTest {
    private final List<ExecutorService> executors = new ArrayList<>();

    @After
    public void stopExecutors() {
        for (ExecutorService executor : executors) executor.shutdownNow();
    }

    @Test
    public void cancelledWatchdogIsRemovedFromSchedulerQueue() throws Exception {
        ScheduledThreadPoolExecutor scheduler = BackupCoordinator.newWatchdogExecutor();
        try {
            Future<?> future = scheduler.schedule(() -> { }, 30L, TimeUnit.MINUTES);
            assertEquals(1, scheduler.getQueue().size());

            future.cancel(false);

            await(() -> scheduler.getQueue().isEmpty());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void rejectsConcurrentRequestAndClearsOnlyAfterUnfreeze() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockBackup = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));
        assertFalse(coordinator.request(new Object(), BackupTrigger.MANUAL));
        assertEquals("worldbackup.message.busy", runtime.messages.get(1));

        runtime.backupRelease.countDown();
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);
        assertEquals(1, runtime.unfreezeCount);
        assertFalse(runtime.activeWasClearDuringUnfreeze);
    }

    @Test
    public void successMessageUsesMonotonicElapsedTime() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        AtomicLong nanos = new AtomicLong();
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog(),
            () -> nanos.getAndAdd(TimeUnit.SECONDS.toNanos(3)));

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);

        assertEquals("worldbackup.message.success", runtime.messages.get(1));
        assertEquals("3.0", runtime.messageArguments.get(1)[0]);
    }

    @Test
    public void workerFailureStillUnfreezesAndCleansSnapshot() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failBackup = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);

        assertEquals(1, runtime.unfreezeCount);
        assertEquals("worldbackup.message.failure", runtime.messages.get(1));
    }

    @Test
    public void stagedSnapshotReleasesWorldsBeforeWorkerReadsIt() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));
        await(() -> !coordinator.isActive());

        int release = runtime.events.indexOf("release-worlds");
        assertTrue(release >= 0);
        assertTrue(release < runtime.events.indexOf("backup-MANUAL"));
        assertTrue(runtime.events.indexOf("backup-MANUAL")
            < runtime.events.indexOf("unfreeze"));
    }

    @Test
    public void masterSwitchRejectsNormalAndExitBackupsWithoutFreezing() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.backupsEnabled = false;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        Object server = new Object();

        assertFalse(coordinator.request(server, BackupTrigger.MANUAL));
        coordinator.onServerStopping(server);

        assertEquals(Collections.singletonList("worldbackup.message.disabled"),
            runtime.messages);
        assertEquals(0, runtime.freezeCount);
        assertTrue(runtime.triggers.isEmpty());
    }

    @Test
    public void watchdogWaitsForWorkerCleanupThenUnfreezesAndReportsTimeout() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockUntilCancelled = true;
        ManualWatchdog watchdog = new ManualWatchdog();
        BackupCoordinator coordinator = coordinator(runtime, watchdog);

        coordinator.request(new Object(), BackupTrigger.MANUAL);
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));
        watchdog.fire();
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);

        assertTrue(runtime.workerCleaned);
        assertEquals(1, runtime.unfreezeCount);
        assertEquals("worldbackup.error.timeout", runtime.messages.get(1));
    }

    @Test
    public void watchdogIsArmedBeforeSnapshotPreparationStarts() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failFreeze = true;
        ManualWatchdog watchdog = new ManualWatchdog(runtime.events);
        BackupCoordinator coordinator = coordinator(runtime, watchdog);

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));

        assertTrue(runtime.events.indexOf("watchdog")
            < runtime.events.indexOf("freeze"));
        assertFalse(coordinator.isActive());
    }

    @Test
    public void watchdogPreventsClaimedWorkerFromWritingAfterUnfreeze() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ClaimedWorkerExecutor executor = new ClaimedWorkerExecutor();
        executors.add(executor);
        ManualWatchdog watchdog = new ManualWatchdog();
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor, watchdog,
            System::nanoTime, TimeUnit.MINUTES.toNanos(30), TimeUnit.SECONDS.toNanos(2));
        runtime.coordinator = coordinator;

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));
        assertTrue(executor.claimed.await(2, TimeUnit.SECONDS));
        watchdog.fire();
        executor.allowDelegate.countDown();
        await(() -> !coordinator.isActive());
        assertTrue(executor.delegateReturned.await(2, TimeUnit.SECONDS));

        assertEquals(1L, runtime.backupEntered.getCount());
        assertFalse(runtime.workerCleaned);
        assertEquals(1, runtime.unfreezeCount);
    }

    @Test
    public void completionSchedulingFailureStillUnfreezesAndClears() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failSecondSchedule = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        Object server = new Object();

        coordinator.request(server, BackupTrigger.MANUAL);
        await(() -> runtime.schedules >= 2);

        assertTrue(coordinator.isActive());
        assertEquals(0, runtime.unfreezeCount);

        await(() -> {
            coordinator.retryPending(server);
            return !coordinator.isActive() && runtime.messages.size() >= 2;
        });
        assertEquals(1, runtime.unfreezeCount);
    }

    @Test
    public void cleanupFailureRetainsOwnershipUntilServerThreadRetrySucceeds()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failUnfreezeOnce = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        Object server = new Object();

        coordinator.request(server, BackupTrigger.MANUAL);
        await(() -> runtime.unfreezeCount == 1);

        assertTrue(coordinator.isActive());
        await(() -> {
            coordinator.retryPending(server);
            return !coordinator.isActive();
        });
        assertEquals(2, runtime.unfreezeCount);
        assertEquals("worldbackup.message.failure",
            runtime.messages.get(runtime.messages.size() - 1));
    }

    @Test
    public void shutdownTimeoutReleasesWorldButRetainsOwnershipUntilWorkerEnds()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.ignoreCancellationUntilReleased = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.MILLISECONDS.toNanos(30));
        runtime.coordinator = coordinator;
        Object server = new Object();
        coordinator.request(server, BackupTrigger.MANUAL);
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));

        coordinator.onServerStopping(server);

        assertTrue(coordinator.isActive());
        assertEquals(1, runtime.releaseWorldsCount);
        assertEquals(0, runtime.cleanupSnapshotCount);
        runtime.backupRelease.countDown();
        await(() -> !coordinator.isActive());
        assertEquals(1, runtime.cleanupSnapshotCount);
    }

    @Test
    public void interruptedShutdownAlsoDefersSnapshotCleanupUntilWorkerEnds()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.ignoreCancellationUntilReleased = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.SECONDS.toNanos(2));
        runtime.coordinator = coordinator;
        Object server = new Object();
        coordinator.request(server, BackupTrigger.MANUAL);
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));

        Thread.currentThread().interrupt();
        try {
            coordinator.onServerStopping(server);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertTrue(coordinator.isActive());
        assertEquals(1, runtime.releaseWorldsCount);
        assertEquals(0, runtime.cleanupSnapshotCount);
        runtime.backupRelease.countDown();
        await(() -> !coordinator.isActive());
        assertEquals(1, runtime.cleanupSnapshotCount);
    }

    @Test
    public void shutdownCancelsActiveWorkRunsExitBackupBeforeRestoringFlags() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockUntilCancelled = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        Object server = new Object();
        coordinator.request(server, BackupTrigger.MANUAL);
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));

        coordinator.onServerStopping(server);

        assertEquals(1, runtime.unfreezeCount);
        assertEquals(2, runtime.freezeCount);
        assertEquals(1, runtime.cleanupSnapshotCount);
        assertEquals(BackupTrigger.EXIT, runtime.triggers.get(runtime.triggers.size() - 1));
        assertTrue(runtime.events.indexOf("worker-cleaned")
            < runtime.events.indexOf("backup-EXIT"));
        assertTrue(runtime.events.indexOf("backup-EXIT")
            < runtime.events.indexOf("unfreeze"));
        assertFalse(coordinator.isActive());
        assertEquals("worldbackup.message.success",
            runtime.messages.get(runtime.messages.size() - 1));
        assertEquals(1,
            runtime.messageArguments.get(runtime.messageArguments.size() - 1).length);
    }

    private BackupCoordinator coordinator(FakeRuntime runtime, ManualWatchdog watchdog) {
        return coordinator(runtime, watchdog, System::nanoTime);
    }

    private BackupCoordinator coordinator(FakeRuntime runtime, ManualWatchdog watchdog,
                                          BackupCoordinator.NanoClock clock) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor, watchdog,
            clock, TimeUnit.MINUTES.toNanos(30), TimeUnit.SECONDS.toNanos(2));
        runtime.coordinator = coordinator;
        return coordinator;
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!check.ok() && System.nanoTime() < deadline) Thread.sleep(5L);
        assertTrue(check.ok());
    }

    private interface Check { boolean ok(); }

    private static final class ManualWatchdog implements BackupCoordinator.Watchdog {
        private final List<String> events;
        private Runnable command;
        private ManualWatchdog() { this(null); }
        private ManualWatchdog(List<String> events) { this.events = events; }
        @Override public BackupCoordinator.Cancellable schedule(Runnable command, long delay) {
            if (events != null) events.add("watchdog");
            this.command = command;
            return () -> { };
        }
        void fire() { command.run(); }
    }

    private static final class ClaimedWorkerExecutor extends AbstractExecutorService {
        private final CountDownLatch claimed = new CountDownLatch(1);
        private final CountDownLatch allowDelegate = new CountDownLatch(1);
        private final CountDownLatch delegateReturned = new CountDownLatch(1);
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private volatile Thread thread;

        @Override public Future<?> submit(Runnable task) {
            ClaimedFuture future = new ClaimedFuture(task);
            thread = new Thread(future::runClaimed, "claimed-worker");
            thread.start();
            return future;
        }

        @Override public void execute(Runnable command) { submit(command); }
        @Override public void shutdown() { shutdown.set(true); }
        @Override public List<Runnable> shutdownNow() {
            shutdown.set(true);
            Thread current = thread;
            if (current != null) current.interrupt();
            allowDelegate.countDown();
            return Collections.emptyList();
        }
        @Override public boolean isShutdown() { return shutdown.get(); }
        @Override public boolean isTerminated() {
            Thread current = thread;
            return shutdown.get() && (current == null || !current.isAlive());
        }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            Thread current = thread;
            if (current != null) current.join(unit.toMillis(timeout));
            return isTerminated();
        }

        private final class ClaimedFuture implements Future<Object> {
            private final Runnable delegate;
            private final AtomicBoolean cancelled = new AtomicBoolean();

            private ClaimedFuture(Runnable delegate) { this.delegate = delegate; }

            private void runClaimed() {
                claimed.countDown();
                boolean waiting = true;
                while (waiting) {
                    try {
                        allowDelegate.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // A claimed worker may observe cancellation before user code starts.
                    }
                }
                try {
                    delegate.run();
                } finally {
                    delegateReturned.countDown();
                }
            }

            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.set(true);
                if (mayInterruptIfRunning && thread != null) thread.interrupt();
                return true;
            }
            @Override public boolean isCancelled() { return cancelled.get(); }
            @Override public boolean isDone() { return false; }
            @Override public Object get() { throw new UnsupportedOperationException(); }
            @Override public Object get(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        }
    }

    private static final class FakeRuntime implements BackupCoordinator.Runtime {
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final List<Object[]> messageArguments = Collections.synchronizedList(new ArrayList<>());
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private final List<BackupTrigger> triggers = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch backupEntered = new CountDownLatch(1);
        private final CountDownLatch backupRelease = new CountDownLatch(1);
        private volatile boolean blockBackup;
        private volatile boolean blockUntilCancelled;
        private volatile boolean ignoreCancellationUntilReleased;
        private volatile boolean workerCleaned;
        private volatile boolean backupsEnabled = true;
        private volatile boolean failFreeze;
        private volatile boolean failBackup;
        private volatile boolean failSecondSchedule;
        private volatile boolean failUnfreezeOnce;
        private volatile int schedules;
        private volatile int freezeCount;
        private volatile int unfreezeCount;
        private volatile int releaseWorldsCount;
        private volatile int cleanupSnapshotCount;
        private volatile boolean activeWasClearDuringUnfreeze;
        private BackupCoordinator coordinator;

        @Override public boolean normalBackupsEnabled(Object server) { return backupsEnabled; }
        @Override public boolean exitBackupEnabled(Object server) { return backupsEnabled; }
        @Override public Object freeze(Object server) {
            freezeCount++;
            events.add("freeze");
            if (failFreeze) throw new IllegalStateException("freeze failed");
            return new Object();
        }
        @Override public void unfreeze(Object snapshot) {
            activeWasClearDuringUnfreeze = !coordinator.isActive();
            unfreezeCount++;
            events.add("unfreeze");
            if (failUnfreezeOnce) {
                failUnfreezeOnce = false;
                throw new IllegalStateException("cleanup failed");
            }
        }
        @Override public void releaseWorlds(Object snapshot) {
            releaseWorldsCount++;
            events.add("release-worlds");
        }
        @Override public void cleanupSnapshot(Object snapshot) {
            cleanupSnapshotCount++;
            events.add("cleanup-snapshot");
        }
        @Override public BackupCoordinator.BackupJob createJob(Object server, Object snapshot,
                                                                BackupTrigger trigger) {
            return new BackupCoordinator.BackupJob(Paths.get("world"), Paths.get("repo"),
                "world", "World", BackupMode.FULL, trigger, 10, 5, 0L);
        }
        @Override public BackupResult run(BackupCoordinator.BackupJob job,
                                          BackupCancellation cancellation) throws Exception {
            triggers.add(job.trigger);
            events.add("backup-" + job.trigger);
            if (failBackup) throw new IOException("backup failed");
            if (job.trigger != BackupTrigger.EXIT) {
                backupEntered.countDown();
                if (blockBackup) backupRelease.await();
                if (ignoreCancellationUntilReleased) {
                    boolean waiting = true;
                    while (waiting) {
                        try {
                            backupRelease.await();
                            waiting = false;
                        } catch (InterruptedException ignored) { }
                    }
                }
                if (blockUntilCancelled) {
                    while (!cancellation.isCancelled()) {
                        try { Thread.sleep(5L); }
                        catch (InterruptedException ignored) { }
                    }
                    workerCleaned = true;
                    events.add("worker-cleaned");
                }
            }
            return BackupResult.success("version", 0L, 0L, 0L);
        }
        @Override public void schedule(Object server, Runnable command) {
            schedules++;
            if (failSecondSchedule && schedules == 2) throw new IllegalStateException("closed");
            command.run();
        }
        @Override public void message(String key, Object... arguments) {
            messages.add(key);
            messageArguments.add(arguments);
        }
    }
}
