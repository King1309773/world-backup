package com.rlcmj.worldbackup.runtime;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import com.rlcmj.worldbackup.core.BackupTrigger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientEventHandlerTest {
    private static final long WORLD_LOAD_DELAY_NANOS = TimeUnit.SECONDS.toNanos(10L);

    @Test
    public void worldLoadTickPolicyWaitsThroughBusyThenRequestsWorldLoadOnce() {
        ClientEventHandler.WorldLoadTickPolicy policy =
            new ClientEventHandler.WorldLoadTickPolicy();
        RecordingRequester requester = new RecordingRequester();
        Object server = new Object();

        policy.tick(server, true, 0L, requester);
        policy.tick(server, true, WORLD_LOAD_DELAY_NANOS - 1L, requester);
        assertEquals(0, requester.requests);

        requester.busy = true;
        policy.tick(server, true, WORLD_LOAD_DELAY_NANOS, requester);
        assertEquals(0, requester.requests);

        requester.busy = false;
        assertTrue(policy.tick(server, true, WORLD_LOAD_DELAY_NANOS + 1L, requester));
        assertEquals(1, requester.requests);
        assertEquals(BackupTrigger.WORLD_LOAD, requester.trigger);
        assertEquals(server, requester.server);

        policy.tick(server, true, WORLD_LOAD_DELAY_NANOS + 2L, requester);
        assertEquals(1, requester.requests);
    }

    @Test
    public void worldLoadTickPolicyRetriesRejectedRequestUntilAccepted() {
        ClientEventHandler.WorldLoadTickPolicy policy =
            new ClientEventHandler.WorldLoadTickPolicy();
        RecordingRequester requester = new RecordingRequester();
        Object server = new Object();

        policy.tick(server, true, 0L, requester);
        requester.accept = false;
        assertFalse(policy.tick(server, true, WORLD_LOAD_DELAY_NANOS, requester));
        assertEquals(1, requester.requests);

        requester.accept = true;
        assertTrue(policy.tick(server, true, WORLD_LOAD_DELAY_NANOS + 1L, requester));
        assertEquals(2, requester.requests);
        policy.tick(server, true, WORLD_LOAD_DELAY_NANOS + 2L, requester);
        assertEquals(2, requester.requests);
    }

    @Test
    public void worldLoadTickPolicyRearmsAfterServerSwitchAndDisable() {
        ClientEventHandler.WorldLoadTickPolicy policy =
            new ClientEventHandler.WorldLoadTickPolicy();
        RecordingRequester requester = new RecordingRequester();
        Object first = new Object();
        Object second = new Object();

        policy.tick(first, true, 0L, requester);
        policy.tick(second, true, WORLD_LOAD_DELAY_NANOS, requester);
        assertEquals(0, requester.requests);
        assertTrue(policy.tick(second, true, WORLD_LOAD_DELAY_NANOS * 2L, requester));
        assertEquals(1, requester.requests);

        policy.tick(second, false, WORLD_LOAD_DELAY_NANOS * 2L + 1L, requester);
        policy.tick(second, true, WORLD_LOAD_DELAY_NANOS * 2L + 2L, requester);
        policy.tick(second, true, WORLD_LOAD_DELAY_NANOS * 3L + 1L, requester);
        assertEquals(1, requester.requests);
        assertTrue(policy.tick(second, true, WORLD_LOAD_DELAY_NANOS * 3L + 2L,
            requester));
        assertEquals(2, requester.requests);
    }

    @Test
    public void worldLoadWaitsTenSecondsAndFiresOnceAfterAcceptance() {
        ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
        Object server = new Object();

        assertFalse(timer.due(server, true, 0L));
        assertFalse(timer.due(server, true, WORLD_LOAD_DELAY_NANOS - 1L));
        assertTrue(timer.due(server, true, WORLD_LOAD_DELAY_NANOS));
        timer.accepted(server);
        assertFalse(timer.due(server, true, WORLD_LOAD_DELAY_NANOS + 1L));
    }

    @Test
    public void pendingWorldLoadRemainsDueUntilAccepted() {
        ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
        Object server = new Object();

        assertFalse(timer.due(server, true, 0L));
        assertTrue(timer.due(server, true, WORLD_LOAD_DELAY_NANOS));
        assertTrue(timer.due(server, true, WORLD_LOAD_DELAY_NANOS + 1L));
        timer.accepted(server);
        assertFalse(timer.due(server, true, WORLD_LOAD_DELAY_NANOS + 2L));
    }

    @Test
    public void worldLoadSwitchingServerIdentityStartsANewDelay() {
        ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
        Object first = new Object();
        Object second = new Object();

        assertFalse(timer.due(first, true, 0L));
        assertTrue(timer.due(first, true, WORLD_LOAD_DELAY_NANOS));
        assertFalse(timer.due(second, true, WORLD_LOAD_DELAY_NANOS));
        assertFalse(timer.due(second, true, WORLD_LOAD_DELAY_NANOS * 2L - 1L));
        assertTrue(timer.due(second, true, WORLD_LOAD_DELAY_NANOS * 2L));
    }

    @Test
    public void leavingAndReenteringSameServerStartsANewDelay() {
        ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
        Object server = new Object();

        assertFalse(timer.due(server, true, 0L));
        assertTrue(timer.due(server, true, WORLD_LOAD_DELAY_NANOS));
        assertFalse(timer.due(null, false, WORLD_LOAD_DELAY_NANOS));
        assertFalse(timer.due(server, true, WORLD_LOAD_DELAY_NANOS + 1L));
        assertFalse(timer.due(server, true, WORLD_LOAD_DELAY_NANOS * 2L));
        assertTrue(timer.due(server, true, WORLD_LOAD_DELAY_NANOS * 2L + 1L));
    }

    @Test
    public void disablingAndReenablingWorldLoadStartsANewDelay() {
        ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
        Object server = new Object();

        assertFalse(timer.due(server, true, 0L));
        assertFalse(timer.due(server, false, WORLD_LOAD_DELAY_NANOS));
        assertFalse(timer.due(server, true, WORLD_LOAD_DELAY_NANOS + 1L));
        assertFalse(timer.due(server, true, WORLD_LOAD_DELAY_NANOS * 2L));
        assertTrue(timer.due(server, true, WORLD_LOAD_DELAY_NANOS * 2L + 1L));
    }

    @Test
    public void worldLoadDeadlineSaturatesOnOverflow() {
        ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
        Object server = new Object();

        assertFalse(timer.due(server, true, Long.MAX_VALUE - 1L));
        assertFalse(timer.due(server, true, Long.MAX_VALUE - 1L));
        assertTrue(timer.due(server, true, Long.MAX_VALUE));
    }

    @Test
    public void acceptingWorldLoadDoesNotMoveScheduledDeadline() {
        ClientEventHandler.WorldLoadTickPolicy worldLoad =
            new ClientEventHandler.WorldLoadTickPolicy();
        ClientEventHandler.BackupTimer scheduled = new ClientEventHandler.BackupTimer();
        RecordingRequester requester = new RecordingRequester();
        Object server = new Object();
        long scheduledInterval = TimeUnit.SECONDS.toNanos(30L);

        assertFalse(worldLoad.tick(server, true, 0L, requester));
        assertFalse(scheduled.due(server, true, scheduledInterval, 0L));
        assertTrue(worldLoad.tick(server, true, WORLD_LOAD_DELAY_NANOS, requester));
        assertFalse(scheduled.due(server, true, scheduledInterval,
            WORLD_LOAD_DELAY_NANOS));
        assertTrue(scheduled.due(server, true, scheduledInterval,
            scheduledInterval));
    }

    private static final class RecordingRequester
            implements ClientEventHandler.BusyRequester {
        private boolean busy;
        private boolean accept = true;
        private int requests;
        private Object server;
        private BackupTrigger trigger;

        @Override public boolean isBusy() { return busy; }

        @Override public boolean request(Object server, BackupTrigger trigger) {
            requests++;
            this.server = server;
            this.trigger = trigger;
            return accept;
        }
    }

    @Test
    public void timerResetsForWorldIdentityAndOnlyFiresWhenDue() {
        ClientEventHandler.BackupTimer timer = new ClientEventHandler.BackupTimer();
        Object first = new Object();
        long interval = TimeUnit.MINUTES.toNanos(20);

        assertFalse(timer.due(first, true, interval, 10L));
        assertFalse(timer.due(first, true, interval, 10L + interval - 1L));
        assertTrue(timer.due(first, true, interval, 10L + interval));
        assertFalse(timer.due(first, true, interval, 10L + interval));

        Object second = new Object();
        assertFalse(timer.due(second, true, interval, 10L + interval));
        assertFalse(timer.due(second, true, interval, 10L + interval * 2L - 1L));
        assertTrue(timer.due(second, true, interval, 10L + interval * 2L));
    }

    @Test
    public void timerDoesNotExistOutsideEnabledLoadedWorld() {
        ClientEventHandler.BackupTimer timer = new ClientEventHandler.BackupTimer();
        Object server = new Object();

        assertFalse(timer.due(server, true, 100L, 0L));
        assertFalse(timer.due(null, true, 100L, 200L));
        assertFalse(timer.due(server, true, 100L, 250L));
        assertFalse(timer.due(server, false, 100L, 500L));
        assertFalse(timer.due(server, true, 100L, 550L));
        assertTrue(timer.due(server, true, 100L, 650L));
    }

    @Test
    public void changingIntervalRearmsFromChangeTime() {
        ClientEventHandler.BackupTimer timer = new ClientEventHandler.BackupTimer();
        Object server = new Object();

        assertFalse(timer.due(server, true, 100L, 0L));
        assertFalse(timer.due(server, true, 500L, 90L));
        assertFalse(timer.due(server, true, 500L, 100L));
        assertFalse(timer.due(server, true, 500L, 589L));
        assertTrue(timer.due(server, true, 500L, 590L));
    }

    @Test
    public void restoreGuardBlocksWorldListInputAndOpeningOnlyWhileRestoreIsActive() {
        assertTrue(ClientEventHandler.RestoreUiGuard.blocks(true, true));
        assertFalse(ClientEventHandler.RestoreUiGuard.blocks(false, true));
        assertFalse(ClientEventHandler.RestoreUiGuard.blocks(true, false));
    }
}
