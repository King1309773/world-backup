package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.config.WorldBackupConfig;
import com.rlcmj.worldbackup.core.BackupTrigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.concurrent.TimeUnit;

public final class ClientEventHandler {
    private final BackupCoordinator coordinator;
    private final KeyBinding manualBackup = new KeyBinding("key.worldbackup.manual",
        Keyboard.KEY_NONE, "key.categories.worldbackup");
    private final BackupTimer timer = new BackupTimer();

    public ClientEventHandler(BackupCoordinator coordinator) {
        this.coordinator = coordinator;
        ClientRegistry.registerKeyBinding(manualBackup);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        IntegratedServer server = minecraft.getIntegratedServer();
        boolean loaded = server != null && minecraft.world != null;
        if (loaded) coordinator.retryPending(server);
        while (manualBackup.isPressed()) {
            if (loaded) coordinator.request(server, BackupTrigger.MANUAL);
        }
        boolean scheduled = loaded && WorldBackupConfig.enabled
            && WorldBackupConfig.scheduledEnabled;
        long interval = TimeUnit.MINUTES.toNanos(
            Math.max(1, WorldBackupConfig.intervalMinutes));
        if (timer.due(scheduled ? server : null, scheduled, interval, System.nanoTime())) {
            coordinator.request(server, BackupTrigger.SCHEDULED);
        }
    }

    static final class BackupTimer {
        private Object serverIdentity;
        private long nextScheduledNanos;
        private long armedIntervalNanos;
        private boolean armed;

        boolean due(Object server, boolean enabled, long intervalNanos, long now) {
            if (server == null || !enabled) {
                serverIdentity = null;
                armed = false;
                return false;
            }
            if (server != serverIdentity || !armed || intervalNanos != armedIntervalNanos) {
                serverIdentity = server;
                armedIntervalNanos = intervalNanos;
                nextScheduledNanos = saturatingAdd(now, intervalNanos);
                armed = true;
                return false;
            }
            if (now - nextScheduledNanos < 0L) return false;
            nextScheduledNanos = saturatingAdd(now, intervalNanos);
            return true;
        }

        private static long saturatingAdd(long value, long increment) {
            if (increment > 0L && value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
            return value + increment;
        }
    }
}
