package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.runtime.BackupCoordinator;
import com.rlcmj.worldbackup.storage.BackupRestorer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;

@SideOnly(Side.CLIENT)
public final class GuiRestoreConfirm extends GuiScreen {
    private final GuiBackupManager manager;
    private final String worldFolder;
    private final Path repository;
    private final BackupVersion version;
    private final BackupCoordinator coordinator;

    GuiRestoreConfirm(GuiBackupManager manager, String worldFolder, Path repository,
                      BackupVersion version, BackupCoordinator coordinator) {
        this.manager = manager;
        this.worldFolder = worldFolder;
        this.repository = repository;
        this.version = version;
        this.coordinator = coordinator;
    }

    @Override public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 102, height / 2 + 38, 100, 20,
            I18n.format("gui.worldbackup.confirm")));
        buttonList.add(new GuiButton(1, width / 2 + 2, height / 2 + 38, 100, 20,
            I18n.format("gui.worldbackup.cancel")));
        updateButtons();
    }

    @Override public void updateScreen() { updateButtons(); }

    private void updateButtons() {
        if (buttonList.isEmpty()) return;
        boolean restoring = coordinator.isRestoreActive();
        buttonList.get(0).enabled = mc.world == null && mc.getIntegratedServer() == null
            && !coordinator.isBusy();
        buttonList.get(1).enabled = !restoring;
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (coordinator.isRestoreActive()) return;
        if (button.id == 1) {
            mc.displayGuiScreen(manager);
            return;
        }
        if (button.id != 0 || !button.enabled || mc.world != null
                || mc.getIntegratedServer() != null) return;
        manager.beginRestore();
        Path game = mc.gameDir.toPath();
        boolean accepted = coordinator.requestRestore(
            () -> {
                if (mc.world != null || mc.getIntegratedServer() != null) {
                    throw new IllegalStateException("world became active before restore");
                }
                return new BackupRestorer().restore(game, game.resolve("saves"), worldFolder,
                    repository, version.getId());
            },
            new BackupCoordinator.RestoreCallback() {
                @Override public void succeeded(Path restored) {
                    mc.displayGuiScreen(manager);
                    manager.restoreSucceeded();
                }
                @Override public void failed(Throwable failure) {
                    mc.displayGuiScreen(manager);
                    manager.restoreFailed(failure);
                }
            });
        if (!accepted) manager.setStatus("worldbackup.message.busy");
        updateButtons();
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (!coordinator.isRestoreActive()) super.keyTyped(typedChar, keyCode);
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, I18n.format("gui.worldbackup.confirm_restore.title"),
            width / 2, height / 2 - 55, 0xFFFFFF);
        String time = DateFormat.getDateTimeInstance().format(new Date(version.getCreatedAt()));
        drawCenteredString(fontRenderer, RestoreConfirmationFormatter.format(time,
            worldFolder, (key, arguments) -> I18n.format(key, arguments)),
            width / 2, height / 2 - 25, 0xDDDDDD);
        drawCenteredString(fontRenderer, I18n.format("gui.worldbackup.confirm_restore.safety"),
            width / 2, height / 2 - 10, 0xFFCC66);
        if (coordinator.isRestoreActive()) {
            drawCenteredString(fontRenderer,
                I18n.format("gui.worldbackup.status.restore_in_progress"),
                width / 2, height / 2 + 18, 0xFFCC66);
        }
        updateButtons();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
