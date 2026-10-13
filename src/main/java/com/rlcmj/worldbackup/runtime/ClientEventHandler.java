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
    private final BackupCoordinator coordinator;
    private final KeyBinding manualBackup = new KeyBinding("key.worldbackup.manual",
        Keyboard.KEY_NONE, "key.categories.worldbackup");
    private final BackupTimer timer = new BackupTimer();
    private final Map<GuiWorldSelection, GuiButton> managerButtons = new WeakHashMap<>();

    public ClientEventHandler(BackupCoordinator coordinator) {
        this.coordinator = coordinator;
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
        boolean scheduled = loaded && WorldBackupConfig.enabled
            && WorldBackupConfig.scheduledEnabled;
        long interval = TimeUnit.MINUTES.toNanos(
            Math.max(1, WorldBackupConfig.intervalMinutes));
        if (timer.due(scheduled ? server : null, scheduled, interval, System.nanoTime())) {
            coordinator.request(server, BackupTrigger.SCHEDULED);
        }
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

    static final class RestoreUiGuard {
        static boolean blocks(boolean restoreActive, boolean worldSelection) {
            return restoreActive && worldSelection;
        }
    }
}
