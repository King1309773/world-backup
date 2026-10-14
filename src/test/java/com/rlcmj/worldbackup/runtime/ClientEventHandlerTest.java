package com.rlcmj.worldbackup.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    public void scheduledWinsWhenBothTimersAreDueAndWorldLoadRetriesNextTick() {
        ClientEventHandler.AutomaticBackupTickPolicy policy =
            new ClientEventHandler.AutomaticBackupTickPolicy();
        RecordingRequester requester = new RecordingRequester();
        requester.becomeBusyAfterRequest = true;
        Object server = new Object();

        policy.tick(server, true, true, WORLD_LOAD_DELAY_NANOS, 0L, requester);
        policy.tick(server, true, true, WORLD_LOAD_DELAY_NANOS,
            WORLD_LOAD_DELAY_NANOS, requester);

        assertEquals(Arrays.asList(BackupTrigger.SCHEDULED), requester.triggers);
        assertEquals(0, requester.silentRequests);

        requester.busy = false;
        policy.tick(server, true, true, WORLD_LOAD_DELAY_NANOS,
            WORLD_LOAD_DELAY_NANOS + 1L, requester);

        assertEquals(Arrays.asList(BackupTrigger.SCHEDULED, BackupTrigger.WORLD_LOAD),
            requester.triggers);
        assertEquals(1, requester.silentRequests);
        policy.tick(server, true, true, WORLD_LOAD_DELAY_NANOS,
            WORLD_LOAD_DELAY_NANOS + 2L, requester);
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
    public void worldLoadDeadlineUsesNanoTimeWraparound() {
        ClientEventHandler.WorldLoadTimer timer = new ClientEventHandler.WorldLoadTimer();
        Object server = new Object();
        long start = Long.MAX_VALUE - 1L;
        long beforeDeadline = start + WORLD_LOAD_DELAY_NANOS - 1L;
        long deadline = start + WORLD_LOAD_DELAY_NANOS;

        assertFalse(timer.due(server, true, start));
        assertFalse(timer.due(server, true, beforeDeadline));
        assertTrue(timer.due(server, true, deadline));
    }

    @Test
    public void acceptingWorldLoadDoesNotMoveScheduledDeadline() {
        ClientEventHandler.AutomaticBackupTickPolicy policy =
            new ClientEventHandler.AutomaticBackupTickPolicy();
        RecordingRequester requester = new RecordingRequester();
        Object server = new Object();
        long scheduledInterval = TimeUnit.SECONDS.toNanos(30L);

        policy.tick(server, true, true, scheduledInterval, 0L, requester);
        policy.tick(server, true, true, scheduledInterval,
            WORLD_LOAD_DELAY_NANOS, requester);
        assertEquals(Arrays.asList(BackupTrigger.WORLD_LOAD), requester.triggers);

        policy.tick(server, true, true, scheduledInterval,
            scheduledInterval - 1L, requester);
        assertEquals(Arrays.asList(BackupTrigger.WORLD_LOAD), requester.triggers);
        policy.tick(server, true, true, scheduledInterval,
            scheduledInterval, requester);
        assertEquals(Arrays.asList(BackupTrigger.WORLD_LOAD, BackupTrigger.SCHEDULED),
            requester.triggers);
    }

    private static final class RecordingRequester
            implements ClientEventHandler.TickRequester {
        private boolean busy;
        private boolean accept = true;
        private boolean becomeBusyAfterRequest;
        private int requests;
        private int silentRequests;
        private Object server;
        private BackupTrigger trigger;
        private final List<BackupTrigger> triggers = new ArrayList<>();

        @Override public boolean isBusy() { return busy; }

        @Override public boolean request(Object server, BackupTrigger trigger) {
            requests++;
            this.server = server;
            this.trigger = trigger;
            triggers.add(trigger);
            if (becomeBusyAfterRequest) busy = true;
            return accept;
        }

        @Override public boolean tryRequestSilently(Object server,
                                                    BackupTrigger trigger) {
            silentRequests++;
            return request(server, trigger);
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
    public void scheduledDeadlineUsesNanoTimeWraparound() {
        ClientEventHandler.BackupTimer timer = new ClientEventHandler.BackupTimer();
        Object server = new Object();
        long interval = TimeUnit.SECONDS.toNanos(10L);
        long start = Long.MAX_VALUE - 1L;

        assertFalse(timer.due(server, true, interval, start));
        assertFalse(timer.due(server, true, interval, start + interval - 1L));
        assertTrue(timer.due(server, true, interval, start + interval));
    }

    @Test
    public void restoreGuardBlocksWorldListInputAndOpeningOnlyWhileRestoreIsActive() {
        assertTrue(ClientEventHandler.RestoreUiGuard.blocks(true, true));
        assertFalse(ClientEventHandler.RestoreUiGuard.blocks(false, true));
        assertFalse(ClientEventHandler.RestoreUiGuard.blocks(true, false));
    }
}
