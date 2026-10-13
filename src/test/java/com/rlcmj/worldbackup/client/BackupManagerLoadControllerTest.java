package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupCancellation;
import org.junit.After;
import org.junit.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupManagerLoadControllerTest {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @After public void stopWorker() {
        worker.shutdownNow();
    }

    @Test
    public void blockingLoadStartsQuicklyAndPublishesOnlyOnClientScheduler() throws Exception {
        Queue<Runnable> clientTasks = new ConcurrentLinkedQueue<>();
        BackupManagerLoadController<String> controller =
            new BackupManagerLoadController<>(worker, clientTasks::add);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> published = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        long started = System.nanoTime();
        controller.start(cancellation -> {
            entered.countDown();
            release.await();
            return "loaded";
        }, completion(published, callbackThread));

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500L);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(controller.isLoading());
        assertFalse(controller.isLoaded());
        assertEquals(null, published.get());

        release.countDown();
        await(() -> !clientTasks.isEmpty());
        assertEquals(null, published.get());
        Thread clientThread = Thread.currentThread();
        clientTasks.remove().run();

        assertEquals("loaded", published.get());
        assertEquals(clientThread, callbackThread.get());
        assertFalse(controller.isLoading());
        assertTrue(controller.isLoaded());
    }

    @Test
    public void cancelledOldGenerationCannotOverwriteNewResult() throws Exception {
        Queue<Runnable> clientTasks = new ConcurrentLinkedQueue<>();
        BackupManagerLoadController<String> controller =
            new BackupManagerLoadController<>(worker, clientTasks::add);
        CountDownLatch oldEntered = new CountDownLatch(1);
        AtomicReference<String> published = new AtomicReference<>();

        controller.start(cancellation -> {
            oldEntered.countDown();
            while (!cancellation.isCancelled()) Thread.yield();
            return "old";
        }, completion(published, new AtomicReference<Thread>()));
        assertTrue(oldEntered.await(2, TimeUnit.SECONDS));

        controller.start(cancellation -> "new",
            completion(published, new AtomicReference<Thread>()));
        await(() -> clientTasks.size() >= 2);
        while (!clientTasks.isEmpty()) clientTasks.remove().run();

        assertEquals("new", published.get());
        assertTrue(controller.isLoaded());
    }

    private static BackupManagerLoadController.Completion<String> completion(
            AtomicReference<String> published, AtomicReference<Thread> callbackThread) {
        return new BackupManagerLoadController.Completion<String>() {
            @Override public void succeeded(String value) {
                callbackThread.set(Thread.currentThread());
                published.set(value);
            }

            @Override public void failed(Throwable failure) {
                throw new AssertionError(failure);
            }
        };
    }

    private static void await(Check condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.ok() && System.nanoTime() < deadline) Thread.yield();
        assertTrue(condition.ok());
    }

    private interface Check { boolean ok(); }
}
