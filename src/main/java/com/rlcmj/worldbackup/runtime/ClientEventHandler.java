package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.config.WorldBackupConfig;
import com.rlcmj.worldbackup.client.GuiBackupManager;
import com.rlcmj.worldbackup.core.BackupTrigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListWorldSelectionEntry;
import net.minecraft.client.gui.GuiWorldSelection;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.WeakHashMap;

public final class ClientEventHandler {
    private static final int MANAGE_BACKUPS = 0x5742;
    private static final long WORLD_LOAD_DELAY_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private final BackupCoordinator coordinator;
    private final KeyBinding manualBackup = new KeyBinding("key.worldbackup.manual",
        Keyboard.KEY_NONE, "key.categories.worldbackup");
    private final AutomaticBackupTickPolicy automaticBackupPolicy =
        new AutomaticBackupTickPolicy();
    private final TickRequester automaticBackupRequester;
    private final Map<GuiWorldSelection, GuiButton> managerButtons = new WeakHashMap<>();

    public ClientEventHandler(BackupCoordinator coordinator) {
        this.coordinator = coordinator;
        this.automaticBackupRequester = new TickRequester() {
            @Override public boolean isBusy() {
                return coordinator.isBusy();
            }

            @Override public boolean request(Object server, BackupTrigger trigger) {
                return coordinator.request(server, trigger);
            }

            @Override public boolean tryRequestSilently(Object server,
                                                        BackupTrigger trigger) {
                return coordinator.requestSilently(server, trigger);
            }
        };
        ClientRegistry.registerKeyBinding(manualBackup);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        coordinator.retryClientCallbacks();
        Minecraft minecraft = Minecraft.getMinecraft();
        IntegratedServer server = minecraft.getIntegratedServer();
        boolean loaded = server != null && minecraft.world != null;
        if (loaded) coordinator.retryPending(server);
        while (manualBackup.isPressed()) {
            if (loaded) coordinator.request(server, BackupTrigger.MANUAL);
        }
        boolean worldLoad = loaded && WorldBackupConfig.enabled
            && WorldBackupConfig.worldLoadEnabled;
        boolean scheduled = loaded && WorldBackupConfig.enabled
            && WorldBackupConfig.scheduledEnabled;
        long interval = TimeUnit.MINUTES.toNanos(
            Math.max(1, WorldBackupConfig.intervalMinutes));
        automaticBackupPolicy.tick(server, worldLoad, scheduled, interval,
            System.nanoTime(), automaticBackupRequester);
    }

    @SubscribeEvent
    public void onWorldSelectionInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.getGui() instanceof GuiWorldSelection)) return;
        GuiButton button = new GuiButton(MANAGE_BACKUPS, event.getGui().width - 105, 8,
            100, 20, I18n.format("gui.worldbackup.manage"));
        button.enabled = selected((GuiWorldSelection) event.getGui()) != null;
        event.getButtonList().add(button);
        managerButtons.put((GuiWorldSelection) event.getGui(), button);
    }

    @SubscribeEvent
    public void onWorldSelectionDraw(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (!(event.getGui() instanceof GuiWorldSelection)) return;
        GuiWorldSelection screen = (GuiWorldSelection) event.getGui();
        if (coordinator.isRestoreActive()) {
            GuiButton manager = managerButtons.get(screen);
            if (manager != null) manager.enabled = false;
            return;
        }
        GuiButton button = managerButtons.get(screen);
        if (button != null) button.enabled = selected(screen) != null;
    }

    @SubscribeEvent
    public void onWorldSelectionAction(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (!(event.getGui() instanceof GuiWorldSelection)
                || event.getButton().id != MANAGE_BACKUPS
                || coordinator.isRestoreActive()) return;
        GuiListWorldSelectionEntry entry = selected((GuiWorldSelection) event.getGui());
        if (entry != null) {
            Minecraft.getMinecraft().displayGuiScreen(new GuiBackupManager(event.getGui(),
                entry.worldSummary.getFileName(), coordinator));
        }
    }

    @SubscribeEvent
    public void onWorldSelectionDrawNotice(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.getGui() instanceof GuiWorldSelection)
                || !coordinator.isRestoreActive()) return;
        GuiWorldSelection screen = (GuiWorldSelection) event.getGui();
        String message = I18n.format("gui.worldbackup.status.restore_in_progress");
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.fontRenderer.drawString(message,
            (screen.width - minecraft.fontRenderer.getStringWidth(message)) / 2,
            12, 0xFFCC66);
    }

    @SubscribeEvent
    public void onWorldSelectionActionPre(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (RestoreUiGuard.blocks(coordinator.isRestoreActive(),
                event.getGui() instanceof GuiWorldSelection)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onWorldSelectionMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (RestoreUiGuard.blocks(coordinator.isRestoreActive(),
                event.getGui() instanceof GuiWorldSelection)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onWorldSelectionKeyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (RestoreUiGuard.blocks(coordinator.isRestoreActive(),
                event.getGui() instanceof GuiWorldSelection)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (RestoreUiGuard.blocks(coordinator.isRestoreActive(),
                event.getGui() instanceof GuiWorldSelection)) {
            event.setCanceled(true);
        }
    }

    private static GuiListWorldSelectionEntry selected(GuiWorldSelection screen) {
        return screen.selectionList == null ? null : screen.selectionList.getSelectedWorld();
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
                nextScheduledNanos = now + intervalNanos;
                armed = true;
                return false;
            }
            if (now - nextScheduledNanos < 0L) return false;
            nextScheduledNanos = now + intervalNanos;
            return true;
        }
    }

    static final class WorldLoadTimer {
        private Object serverIdentity;
        private long deadlineNanos;
        private boolean armed;
        private boolean accepted;

        boolean due(Object server, boolean enabled, long now) {
            if (server == null || !enabled) {
                serverIdentity = null;
                armed = false;
                accepted = false;
                return false;
            }
            if (server != serverIdentity || !armed) {
                serverIdentity = server;
                deadlineNanos = now + WORLD_LOAD_DELAY_NANOS;
                armed = true;
                accepted = false;
                return false;
            }
            return !accepted && now - deadlineNanos >= 0L;
        }

        void accepted(Object server) {
            if (armed && server == serverIdentity) accepted = true;
        }
    }

    interface BusyRequester {
        boolean isBusy();
        boolean tryRequestSilently(Object server, BackupTrigger trigger);
    }

    interface TickRequester extends BusyRequester {
        boolean request(Object server, BackupTrigger trigger);
    }

    static final class WorldLoadTickPolicy {
        private final WorldLoadTimer timer = new WorldLoadTimer();

        boolean tick(Object server, boolean enabled, long now,
                     BusyRequester requester) {
            if (!timer.due(enabled ? server : null, enabled, now)
                    || requester.isBusy()) return false;
            if (!requester.tryRequestSilently(server, BackupTrigger.WORLD_LOAD)) {
                return false;
            }
            timer.accepted(server);
            return true;
        }
    }

    static final class AutomaticBackupTickPolicy {
        private final BackupTimer scheduledTimer = new BackupTimer();
        private final WorldLoadTickPolicy worldLoadPolicy = new WorldLoadTickPolicy();

        void tick(Object server, boolean worldLoadEnabled, boolean scheduledEnabled,
                  long intervalNanos, long now, TickRequester requester) {
            if (scheduledTimer.due(scheduledEnabled ? server : null, scheduledEnabled,
                    intervalNanos, now)) {
                requester.request(server, BackupTrigger.SCHEDULED);
            }
            worldLoadPolicy.tick(server, worldLoadEnabled, now, requester);
        }
    }

    static final class RestoreUiGuard {
        static boolean blocks(boolean restoreActive, boolean worldSelection) {
            return restoreActive && worldSelection;
        }
    }
}
