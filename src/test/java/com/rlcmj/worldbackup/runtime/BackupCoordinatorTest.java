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
import java.util.concurrent.atomic.AtomicReference;

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
    public void worldLoadSuccessMessageUsesMonotonicElapsedTime() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        AtomicLong nanos = new AtomicLong();
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog(),
            () -> nanos.getAndAdd(TimeUnit.SECONDS.toNanos(3)));

        assertTrue(coordinator.request(new Object(), BackupTrigger.WORLD_LOAD));
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);

        assertEquals("worldbackup.message.success", runtime.messages.get(1));
        assertEquals("3.0", runtime.messageArguments.get(1)[0]);
        assertEquals(Collections.singletonList(BackupTrigger.WORLD_LOAD), runtime.triggers);
    }

    @Test
    public void worldLoadFailureStillReportsElapsedTimeAndCleansSnapshot() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failBackup = true;
        AtomicLong nanos = new AtomicLong();
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog(),
            () -> nanos.getAndAdd(TimeUnit.SECONDS.toNanos(4)));

        assertTrue(coordinator.request(new Object(), BackupTrigger.WORLD_LOAD));
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);

        assertEquals(1, runtime.unfreezeCount);
        assertEquals("worldbackup.message.failure", runtime.messages.get(1));
        assertEquals("4.0", runtime.messageArguments.get(1)[0]);
        assertEquals(Collections.singletonList(BackupTrigger.WORLD_LOAD), runtime.triggers);
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
        AtomicLong nanos = new AtomicLong();
        BackupCoordinator coordinator = coordinator(runtime, watchdog,
            () -> nanos.getAndAdd(TimeUnit.SECONDS.toNanos(5L)));

        coordinator.request(new Object(), BackupTrigger.MANUAL);
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));
        watchdog.fire();
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);

        assertTrue(runtime.workerCleaned);
        assertEquals(1, runtime.unfreezeCount);
        assertEquals("worldbackup.error.timeout", runtime.messages.get(1));
        assertEquals(1, runtime.messageArguments.get(1).length);
        assertEquals("5.0", runtime.messageArguments.get(1)[0]);
    }

    @Test
    public void silentWorldLoadRaceRejectsWithoutBusyMessage() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockBackup = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));
        int messagesBeforeRace = runtime.messages.size();

        assertFalse(coordinator.requestSilently(new Object(), BackupTrigger.WORLD_LOAD));
        assertEquals(messagesBeforeRace, runtime.messages.size());

        runtime.backupRelease.countDown();
        await(() -> !coordinator.isActive());
    }

    @Test
    public void watchdogRestoresWorldWhileUncooperativeStagingWorkerRemainsOwned()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockStagingUntilReleased = true;
        ManualWatchdog watchdog = new ManualWatchdog();
        BackupCoordinator coordinator = coordinator(runtime, watchdog);
        Thread requester = new Thread(() ->
            coordinator.request(new Object(), BackupTrigger.MANUAL), "server-thread");
        requester.start();
        assertTrue(runtime.stagingEntered.await(2, TimeUnit.SECONDS));

        watchdog.fire();
        requester.join(2000L);

        assertFalse("server thread stayed blocked in staging", requester.isAlive());
        assertEquals(1, runtime.releaseWorldsCount);
        assertTrue(coordinator.isActive());
        assertEquals(1L, runtime.backupEntered.getCount());

        runtime.stagingRelease.countDown();
        await(() -> !coordinator.isActive() && runtime.messages.size() >= 2);
        assertEquals(1, runtime.cleanupSnapshotCount);
        assertEquals("worldbackup.error.timeout",
            runtime.messages.get(runtime.messages.size() - 1));
    }

    @Test
    public void watchdogBeforeQueuedPreparationReleasesOwnership() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.deferFirstSchedule = true;
        ManualWatchdog watchdog = new ManualWatchdog();
        BackupCoordinator coordinator = coordinator(runtime, watchdog);

        assertTrue(coordinator.request(new Object(), BackupTrigger.MANUAL));
        watchdog.fire();
        runtime.runDeferredSchedule();

        await(() -> !coordinator.isActive());
        assertEquals(0, runtime.freezeCount);
        assertEquals("worldbackup.error.timeout",
            runtime.messages.get(runtime.messages.size() - 1));
    }

    @Test
    public void watchdogInterruptsPrepareAndPreventsStaging() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockPrepareUntilInterrupted = true;
        runtime.returnFromPrepareWithInterruptSet = true;
        ManualWatchdog watchdog = new ManualWatchdog();
        BackupCoordinator coordinator = coordinator(runtime, watchdog);
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        Thread requester = new Thread(() -> {
            coordinator.request(new Object(), BackupTrigger.MANUAL);
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        }, "server-thread");
        requester.start();
        assertTrue(runtime.prepareEntered.await(2, TimeUnit.SECONDS));

        try {
            watchdog.fire();
            requester.join(500L);

            assertFalse("server prepare thread ignored watchdog interrupt", requester.isAlive());
            assertTrue(runtime.prepareFlagsRestored);
            assertEquals(0, runtime.stageCalls);
            await(() -> !coordinator.isActive());
            assertFalse("coordinator interrupt leaked into the server loop",
                interruptedAfterReturn.get());
        } finally {
            runtime.prepareRelease.countDown();
            requester.join(2000L);
        }
    }

    @Test
    public void externalInterruptAtBeginIsNotConsumedAsCoordinatorOwned() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failPrepareOnExternalInterrupt = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        Thread requester = new Thread(() -> {
            Thread.currentThread().interrupt();
            coordinator.request(new Object(), BackupTrigger.MANUAL);
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            Thread.interrupted();
        }, "externally-interrupted-server-thread");

        requester.start();
        requester.join(2000L);

        assertFalse(requester.isAlive());
        assertTrue(interruptedAfterReturn.get());
        assertEquals(0, runtime.stageCalls);
        assertFalse(coordinator.isActive());
    }

    @Test
    public void stoppingCancelsUnclaimedQueuedPreparationWithoutWaiting() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.deferFirstSchedule = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        Object server = new Object();
        assertTrue(coordinator.request(server, BackupTrigger.MANUAL));

        long started = System.nanoTime();
        coordinator.onServerStopping(server);
        long elapsed = System.nanoTime() - started;

        assertTrue("stop waited for an unclaimed queued preparation",
            elapsed < TimeUnit.MILLISECONDS.toNanos(500));
        assertFalse(coordinator.isActive());
        assertEquals(Collections.singletonList(BackupTrigger.EXIT), runtime.triggers);
        assertEquals(1, runtime.freezeCount);

        runtime.runDeferredSchedule();
        assertEquals(1, runtime.freezeCount);
        assertEquals(Collections.singletonList(BackupTrigger.EXIT), runtime.triggers);
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
    public void deferredCleanupRetriesThenReleasesOwnership() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.ignoreCancellationUntilReleased = true;
        runtime.cleanupFailuresRemaining = 1;
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

        runtime.backupRelease.countDown();
        await(() -> !coordinator.isActive());

        assertEquals(2, runtime.cleanupSnapshotCount);
    }

    @Test
    public void permanentDeferredCleanupFailureEventuallyReleasesOwnership()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.ignoreCancellationUntilReleased = true;
        runtime.cleanupFailuresRemaining = Integer.MAX_VALUE;
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

        runtime.backupRelease.countDown();
        await(() -> !coordinator.isActive());

        assertEquals(3, runtime.cleanupSnapshotCount);
        assertEquals("worldbackup.message.failure",
            runtime.messages.get(runtime.messages.size() - 1));
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

    @Test
    public void shutdownArbitrationIsBusyAndSuppressesWorldLoadRequests() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockExit = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        Object server = new Object();
        ClientEventHandler.WorldLoadTickPolicy policy =
            new ClientEventHandler.WorldLoadTickPolicy();
        ClientEventHandler.TickRequester requester =
            new ClientEventHandler.TickRequester() {
                @Override public boolean isBusy() {
                    return coordinator.isBusy();
                }

                @Override public boolean request(Object requestedServer,
                                                 BackupTrigger trigger) {
                    return coordinator.request(requestedServer, trigger);
                }

                @Override public boolean tryRequestSilently(Object requestedServer,
                                                            BackupTrigger trigger) {
                    return coordinator.requestSilently(requestedServer, trigger);
                }
            };
        policy.tick(server, true, 0L, requester);

        Thread stopping = new Thread(() -> coordinator.onServerStopping(server));
        stopping.start();
        assertTrue(runtime.exitEntered.await(2, TimeUnit.SECONDS));
        int messagesBeforeTick = runtime.messages.size();

        assertTrue(coordinator.isBusy());
        assertFalse(policy.tick(server, true, TimeUnit.SECONDS.toNanos(10L), requester));
        assertEquals(messagesBeforeTick, runtime.messages.size());
        assertEquals(Collections.singletonList(BackupTrigger.EXIT), runtime.triggers);

        runtime.exitRelease.countDown();
        stopping.join(2000L);
        assertFalse(stopping.isAlive());
        assertFalse(coordinator.isBusy());
        assertTrue(coordinator.request(server, BackupTrigger.WORLD_LOAD));
        await(() -> !coordinator.isActive());
        assertEquals(BackupTrigger.WORLD_LOAD,
            runtime.triggers.get(runtime.triggers.size() - 1));
    }

    @Test
    public void restoreAndBackupRequestsAreMutuallyExclusiveAndRestoreCompletesOnClientThread()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.SECONDS.toNanos(2));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        assertTrue(coordinator.requestRestore(() -> {
            entered.countDown();
            release.await();
            return Paths.get("restored");
        }, new BackupCoordinator.RestoreCallback() {
            @Override public void succeeded(java.nio.file.Path restored) {
                assertEquals(Paths.get("restored"), restored);
                assertFalse(coordinator.isBusy());
                completed.countDown();
            }
            @Override public void failed(Throwable failure) { throw new AssertionError(failure); }
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertFalse(coordinator.request(new Object(), BackupTrigger.MANUAL));
        assertFalse(coordinator.requestRestore(() -> Paths.get("other"),
            new BackupCoordinator.RestoreCallback() {
                @Override public void succeeded(java.nio.file.Path restored) { }
                @Override public void failed(Throwable failure) { }
            }));
        release.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertFalse(coordinator.isBusy());
    }

    @Test
    public void activeBackupRejectsRestoreRequest() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockBackup = true;
        BackupCoordinator coordinator = coordinator(runtime, new ManualWatchdog());
        coordinator.request(new Object(), BackupTrigger.MANUAL);
        assertTrue(runtime.backupEntered.await(2, TimeUnit.SECONDS));

        assertFalse(coordinator.requestRestore(() -> Paths.get("restored"),
            new BackupCoordinator.RestoreCallback() {
                @Override public void succeeded(java.nio.file.Path restored) { }
                @Override public void failed(Throwable failure) { }
            }));
        runtime.backupRelease.countDown();
    }

    @Test
    public void failedClientSchedulingQueuesRestoreCallbackUntilClientTickRetriesIt()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failClientSchedule = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.SECONDS.toNanos(2));
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CountDownLatch operationFinished = new CountDownLatch(1);

        assertTrue(coordinator.requestRestore(() -> {
            operationFinished.countDown();
            return Paths.get("restored");
        }, new BackupCoordinator.RestoreCallback() {
            @Override public void succeeded(java.nio.file.Path restored) {
                callbackThread.set(Thread.currentThread());
            }
            @Override public void failed(Throwable failure) { throw new AssertionError(failure); }
        }));
        assertTrue(operationFinished.await(2, TimeUnit.SECONDS));
        await(() -> runtime.clientScheduleAttempts > 0);

        assertTrue(coordinator.isRestoreActive());
        assertEquals(null, callbackThread.get());
        runtime.failClientSchedule = false;
        Thread clientThread = Thread.currentThread();
        coordinator.retryClientCallbacks();

        assertEquals(clientThread, callbackThread.get());
        assertFalse(coordinator.isRestoreActive());
    }

    @Test
    public void maintenanceRunsOnWorkerAndExcludesBackupAndRestoreUntilClientCallback()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.deferClientSchedule = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.SECONDS.toNanos(2));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> operationThread = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        assertTrue(coordinator.requestMaintenance(() -> {
            operationThread.set(Thread.currentThread());
            entered.countDown();
            release.await();
            return "done";
        }, new BackupCoordinator.MaintenanceCallback<String>() {
            @Override public void succeeded(String result) {
                assertEquals("done", result);
                callbackThread.set(Thread.currentThread());
            }
            @Override public void failed(Throwable failure) { throw new AssertionError(failure); }
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertFalse(coordinator.request(new Object(), BackupTrigger.MANUAL));
        assertFalse(coordinator.requestRestore(() -> Paths.get("restore"), noRestoreCallback()));
        assertTrue(coordinator.isBusy());
        release.countDown();
        await(() -> runtime.deferredClientSchedule != null);
        assertTrue(coordinator.isBusy());
        runtime.runDeferredClientSchedule();

        assertFalse(operationThread.get().equals(callbackThread.get()));
        assertFalse(coordinator.isBusy());
    }

    @Test
    public void maintenanceClientScheduleFailureStaysActiveUntilTickRetry() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failClientSchedule = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.SECONDS.toNanos(2));
        AtomicBoolean callback = new AtomicBoolean();

        assertTrue(coordinator.requestMaintenance(() -> "done",
            new BackupCoordinator.MaintenanceCallback<String>() {
                @Override public void succeeded(String result) { callback.set(true); }
                @Override public void failed(Throwable failure) { throw new AssertionError(failure); }
            }));
        await(() -> runtime.clientScheduleAttempts > 0);
        assertTrue(coordinator.isBusy());
        assertFalse(callback.get());

        runtime.failClientSchedule = false;
        coordinator.retryClientCallbacks();
        assertTrue(callback.get());
        assertFalse(coordinator.isBusy());
    }

    @Test
    public void shutdownSkipsExitWithoutOverlappingBlockedMaintenance() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.MILLISECONDS.toNanos(30));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(coordinator.requestMaintenance(() -> {
            entered.countDown();
            release.await();
            runtime.events.add("maintenance-finished");
            return "done";
        }, noMaintenanceCallback()));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        coordinator.onServerStopping(new Object());

        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
        assertFalse(runtime.triggers.contains(BackupTrigger.EXIT));
        assertEquals("worldbackup.message.exit_skipped_busy",
            runtime.messages.get(runtime.messages.size() - 1));
        release.countDown();
    }

    @Test
    public void shutdownWaitsForMaintenanceBeforeExitAndLateClientCallbackIsIdempotent()
            throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.deferClientSchedule = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.SECONDS.toNanos(2));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong callbacks = new AtomicLong();
        assertTrue(coordinator.requestMaintenance(() -> {
            entered.countDown();
            release.await();
            runtime.events.add("maintenance-finished");
            return "done";
        }, new BackupCoordinator.MaintenanceCallback<String>() {
            @Override public void succeeded(String value) { callbacks.incrementAndGet(); }
            @Override public void failed(Throwable failure) { throw new AssertionError(failure); }
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread stopping = new Thread(() -> coordinator.onServerStopping(new Object()));
        stopping.start();
        Thread.yield();
        assertFalse(runtime.triggers.contains(BackupTrigger.EXIT));

        release.countDown();
        stopping.join(2000L);

        assertFalse(stopping.isAlive());
        assertTrue(runtime.events.indexOf("maintenance-finished")
            < runtime.events.indexOf("backup-EXIT"));
        assertFalse(coordinator.isBusy());
        runtime.runDeferredClientSchedule();
        assertEquals(1L, callbacks.get());
        assertFalse(coordinator.isBusy());
    }

    @Test
    public void shutdownSkipsExitWhileRestoreOwnsWorker() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        BackupCoordinator coordinator = new BackupCoordinator(runtime, executor,
            new ManualWatchdog(), System::nanoTime, TimeUnit.MINUTES.toNanos(30),
            TimeUnit.MILLISECONDS.toNanos(30));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(coordinator.requestRestore(() -> {
            entered.countDown();
            release.await();
            return Paths.get("restored");
        }, noRestoreCallback()));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        coordinator.onServerStopping(new Object());

        assertFalse(runtime.triggers.contains(BackupTrigger.EXIT));
        assertEquals("worldbackup.message.exit_skipped_busy",
            runtime.messages.get(runtime.messages.size() - 1));
        release.countDown();
    }

    private static BackupCoordinator.RestoreCallback noRestoreCallback() {
        return new BackupCoordinator.RestoreCallback() {
            @Override public void succeeded(java.nio.file.Path restored) { }
            @Override public void failed(Throwable failure) { }
        };
    }

    private static BackupCoordinator.MaintenanceCallback<String> noMaintenanceCallback() {
        return new BackupCoordinator.MaintenanceCallback<String>() {
            @Override public void succeeded(String value) { }
            @Override public void failed(Throwable failure) { }
        };
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
        private final CountDownLatch exitEntered = new CountDownLatch(1);
        private final CountDownLatch exitRelease = new CountDownLatch(1);
        private final CountDownLatch stagingEntered = new CountDownLatch(1);
        private final CountDownLatch stagingRelease = new CountDownLatch(1);
        private final CountDownLatch prepareEntered = new CountDownLatch(1);
        private final CountDownLatch prepareRelease = new CountDownLatch(1);
        private volatile boolean blockBackup;
        private volatile boolean blockExit;
        private volatile boolean blockUntilCancelled;
        private volatile boolean ignoreCancellationUntilReleased;
        private volatile boolean workerCleaned;
        private volatile boolean backupsEnabled = true;
        private volatile boolean failFreeze;
        private volatile boolean failBackup;
        private volatile boolean failSecondSchedule;
        private volatile boolean failUnfreezeOnce;
        private volatile boolean blockStagingUntilReleased;
        private volatile boolean deferFirstSchedule;
        private volatile boolean blockPrepareUntilInterrupted;
        private volatile boolean returnFromPrepareWithInterruptSet;
        private volatile boolean failPrepareOnExternalInterrupt;
        private volatile boolean failClientSchedule;
        private volatile boolean deferClientSchedule;
        private volatile Runnable deferredClientSchedule;
        private volatile boolean prepareFlagsRestored;
        private volatile Runnable deferredSchedule;
        private volatile int cleanupFailuresRemaining;
        private volatile int schedules;
        private volatile int clientScheduleAttempts;
        private volatile int freezeCount;
        private volatile int unfreezeCount;
        private volatile int releaseWorldsCount;
        private volatile int cleanupSnapshotCount;
        private volatile int stageCalls;
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
        @Override public Object prepareSnapshot(Object server) throws Exception {
            if (failPrepareOnExternalInterrupt && Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("external interrupt");
            }
            if (blockPrepareUntilInterrupted) {
                prepareEntered.countDown();
                if (returnFromPrepareWithInterruptSet) {
                    while (!Thread.currentThread().isInterrupted()
                            && prepareRelease.getCount() != 0L) {
                        Thread.yield();
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        prepareFlagsRestored = true;
                        return freeze(server);
                    }
                }
                try {
                    prepareRelease.await();
                } catch (InterruptedException interrupted) {
                    prepareFlagsRestored = true;
                    throw interrupted;
                }
            }
            return freeze(server);
        }
        @Override public Object stageSnapshot(Object prepared,
                                              BackupCancellation cancellation) {
            stageCalls++;
            if (blockStagingUntilReleased) {
                stagingEntered.countDown();
                boolean waiting = true;
                while (waiting) {
                    try {
                        stagingRelease.await();
                        waiting = false;
                    } catch (InterruptedException ignored) { }
                }
            }
            return prepared;
        }
        @Override public void releasePrepared(Object prepared) {
            releaseWorlds(prepared);
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
            if (cleanupFailuresRemaining > 0) {
                cleanupFailuresRemaining--;
                throw new IllegalStateException("cleanup failed");
            }
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
            if (job.trigger == BackupTrigger.EXIT && blockExit) {
                exitEntered.countDown();
                exitRelease.await();
            }
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
            if (deferFirstSchedule && schedules == 1) {
                deferredSchedule = command;
                return;
            }
            command.run();
        }
        @Override public void scheduleClient(Runnable command) {
            clientScheduleAttempts++;
            if (failClientSchedule) throw new IllegalStateException("client unavailable");
            if (deferClientSchedule) {
                deferredClientSchedule = command;
                return;
            }
            command.run();
        }
        private void runDeferredClientSchedule() {
            Runnable command = deferredClientSchedule;
            if (command == null) throw new AssertionError("no deferred client callback");
            deferredClientSchedule = null;
            command.run();
        }
        private void runDeferredSchedule() {
            Runnable command = deferredSchedule;
            if (command == null) throw new AssertionError("no deferred schedule");
            command.run();
        }
        @Override public void message(String key, Object... arguments) {
            messages.add(key);
            messageArguments.add(arguments);
        }
    }
}
