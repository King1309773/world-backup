package com.rlcmj.worldbackup.runtime;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientEventHandlerTest {
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
}
