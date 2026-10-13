package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.config.ConfigSnapshot;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.runtime.BackupCoordinator;
import com.rlcmj.worldbackup.storage.BackupRestorer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SideOnly(Side.CLIENT)
public final class GuiBackupManager extends GuiScreen implements GuiYesNoCallback {
    private static final int RESTORE = 1;
    private static final int DELETE = 2;
    private static final int SAFETY = 3;
    private static final int DONE = 4;
    private static final int PREVIOUS = 5;
    private static final int NEXT = 6;
    private static final int CONFIRM_BACKUP_DELETE = 20;
    private static final int CONFIRM_SAFETY_DELETE = 21;
    private static final int ROW_HEIGHT = 43;

    private final GuiScreen parent;
    private final String worldFolder;
    private final BackupCoordinator coordinator;
    private final BackupManagerService manager = new BackupManagerService();
    private final BackupRestorer restorer = new BackupRestorer();
    private final SafetyCopyStore safetyStore = new SafetyCopyStore();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Path> safetyCopies = new ArrayList<>();
    private Path repository;
    private int selected = -1;
    private int page;
    private boolean safetyMode;
    private boolean catalogIdentityValidated;
    private String statusKey;
    private Object[] statusArguments = new Object[0];

    public GuiBackupManager(GuiScreen parent, String worldFolder,
                            BackupCoordinator coordinator) {
        this.parent = parent;
        this.worldFolder = worldFolder;
        this.coordinator = coordinator;
    }

    @Override public void initGui() {
        buttonList.clear();
        int y = height - 28;
        buttonList.add(new GuiButton(RESTORE, width / 2 - 204, y, 100, 20,
            I18n.format("gui.worldbackup.restore")));
        buttonList.add(new GuiButton(DELETE, width / 2 - 100, y, 100, 20,
            safetyMode ? I18n.format("gui.worldbackup.delete_safety")
                : I18n.format("gui.worldbackup.delete")));
        buttonList.add(new GuiButton(SAFETY, width / 2 + 4, y, 100, 20,
            safetyMode ? I18n.format("gui.worldbackup.backups")
                : I18n.format("gui.worldbackup.safety_copies")));
        buttonList.add(new GuiButton(DONE, width / 2 + 108, y, 96, 20,
            I18n.format("gui.worldbackup.done")));
        buttonList.add(new GuiButton(PREVIOUS, width / 2 - 45, height - 52, 40, 20, "<"));
        buttonList.add(new GuiButton(NEXT, width / 2 + 5, height - 52, 40, 20, ">"));
        if (repository == null) reload();
        updateButtons();
    }

    public void reload() {
        entries.clear();
        safetyCopies.clear();
        selected = -1;
        page = 0;
        statusKey = null;
        statusArguments = new Object[0];
        catalogIdentityValidated = false;
        try {
            Path game = mc.gameDir.toPath();
            repository = ConfigSnapshot.capture(game).getBackupRoot().resolve(worldFolder);
            if (safetyMode) {
                safetyCopies.addAll(safetyStore.list(game, worldFolder));
            } else {
                if (!Files.isRegularFile(repository.resolve("index.json"),
                        LinkOption.NOFOLLOW_LINKS)) {
                    updateButtons();
                    return;
                }
                BackupCatalog catalog = manager.load(repository, worldFolder);
                catalogIdentityValidated = true;
                for (BackupVersion version : catalog.getVersions()) {
                    if (!version.isVisible()) continue;
                    String error = null;
                    try {
                        restorer.validateAvailability(repository, worldFolder, version.getId());
                    } catch (BackupException failure) {
                        error = failure.getTranslationKey();
                    }
                    entries.add(new Entry(version, error));
                }
                entries.sort(Comparator.comparingLong(
                    (Entry value) -> value.version.getCreatedAt()).reversed());
            }
        } catch (BackupException | IllegalArgumentException failure) {
            statusKey = errorKey(failure);
        }
        updateButtons();
    }

    @Override public void updateScreen() {
        updateButtons();
    }

    private void updateButtons() {
        Entry entry = selectedEntry();
        boolean worldClosed = mc != null && mc.world == null
            && mc.getIntegratedServer() == null;
        find(RESTORE).visible = !safetyMode;
        find(RESTORE).enabled = !safetyMode && entry != null && entry.errorKey == null
            && catalogIdentityValidated && worldClosed && !coordinator.isBusy();
        find(DELETE).enabled = safetyMode
            ? selected >= 0 && !coordinator.isBusy()
            : backupDestructiveEnabled(selected >= 0, catalogIdentityValidated,
                coordinator.isBusy());
        int pages = pageCount();
        find(PREVIOUS).enabled = page > 0;
        find(NEXT).enabled = page + 1 < pages;
    }

    private GuiButton find(int id) {
        for (GuiButton button : buttonList) if (button.id == id) return button;
        throw new IllegalStateException("missing button " + id);
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) return;
        if (button.id == DONE) mc.displayGuiScreen(parent);
        else if (button.id == SAFETY) {
            safetyMode = !safetyMode;
            initGui();
            reload();
        } else if (button.id == PREVIOUS) {
            page--;
            selected = -1;
        } else if (button.id == NEXT) {
            page++;
            selected = -1;
        } else if (button.id == RESTORE && selectedEntry() != null) {
            mc.displayGuiScreen(new GuiRestoreConfirm(this, worldFolder, repository,
                selectedEntry().version, coordinator));
        } else if (button.id == DELETE && selected >= 0) {
            String name = safetyMode ? safetyCopies.get(selected).getFileName().toString()
                : selectedEntry().version.getId();
            String title = I18n.format(safetyMode
                ? "gui.worldbackup.confirm_safety_delete.title"
                : "gui.worldbackup.confirm_delete.title");
            String message = I18n.format(safetyMode
                ? "gui.worldbackup.confirm_safety_delete.message"
                : "gui.worldbackup.confirm_delete.message", name);
            mc.displayGuiScreen(new GuiYesNo(this, title, message,
                safetyMode ? CONFIRM_SAFETY_DELETE : CONFIRM_BACKUP_DELETE));
        }
        updateButtons();
    }

    @Override public void confirmClicked(boolean result, int id) {
        mc.displayGuiScreen(this);
        if (!result || coordinator.isBusy()) return;
        try {
            if (id == CONFIRM_BACKUP_DELETE && selectedEntry() != null) {
                BackupManagerService.DeleteResult deleted = manager.delete(repository,
                    worldFolder, selectedEntry().version.getId());
                reload();
                if (!deleted.getWarnings().isEmpty()) {
                    setStatus("gui.worldbackup.status.delete_warning",
                        deleted.getWarnings().size());
                }
            } else if (id == CONFIRM_SAFETY_DELETE && selected >= 0) {
                safetyStore.delete(mc.gameDir.toPath(), worldFolder,
                    safetyCopies.get(selected).getFileName().toString());
                reload();
            }
        } catch (BackupException failure) {
            setStatus(failure.getTranslationKey());
        }
    }

    void beginRestore() {
        setStatus("gui.worldbackup.status.restoring");
        updateButtons();
    }

    void restoreSucceeded() {
        mc.getSaveLoader().flushCache();
        reload();
        setStatus("worldbackup.message.restore_success");
    }

    void restoreFailed(Throwable failure) {
        setStatus("worldbackup.message.restore_failure",
            I18n.format(errorKey(failure)));
    }

    void setStatus(String key, Object... arguments) {
        statusKey = key;
        statusArguments = arguments;
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        int firstY = 34;
        int visibleRows = visibleRows();
        int row = (mouseY - firstY) / ROW_HEIGHT;
        if (mouseY >= firstY && row >= 0 && row < visibleRows) {
            int index = page * visibleRows + row;
            if (index < itemCount()) selected = index;
        }
        updateButtons();
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer,
            I18n.format(safetyMode ? "gui.worldbackup.safety_title"
                : "gui.worldbackup.title", worldFolder), width / 2, 12, 0xFFFFFF);
        int rows = visibleRows();
        int start = page * rows;
        for (int row = 0; row < rows && start + row < itemCount(); row++) {
            int index = start + row;
            int top = 34 + row * ROW_HEIGHT;
            drawRect(width / 2 - 210, top, width / 2 + 210, top + 39,
                index == selected ? 0xAA506080 : 0x88404040);
            if (safetyMode) drawSafety(safetyCopies.get(index), top);
            else drawEntry(entries.get(index), top);
        }
        if (itemCount() == 0 && statusKey == null) {
            drawCenteredString(fontRenderer, I18n.format("gui.worldbackup.status.empty"),
                width / 2, 55, 0xAAAAAA);
        }
        if (statusKey != null) {
            drawCenteredString(fontRenderer, I18n.format(statusKey, statusArguments),
                width / 2, height - 64, 0xFF7777);
        }
        updateButtons();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawSafety(Path copy, int top) {
        fontRenderer.drawString(copy.getFileName().toString(), width / 2 - 202,
            top + 8, 0xFFFFFF);
        fontRenderer.drawString(I18n.format("gui.worldbackup.safety_hint"),
            width / 2 - 202, top + 23, 0xAAAAAA);
    }

    private void drawEntry(Entry entry, int top) {
        BackupVersion version = entry.version;
        String time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM,
            Locale.getDefault()).format(new Date(version.getCreatedAt()));
        fontRenderer.drawString(time + "  "
                + I18n.format("worldbackup.trigger." + version.getTrigger().name().toLowerCase(Locale.ROOT))
                + " / " + I18n.format("worldbackup.mode." + version.getMode().name().toLowerCase(Locale.ROOT)),
            width / 2 - 202, top + 4, 0xFFFFFF);
        fontRenderer.drawString(I18n.format("gui.worldbackup.details", bytes(version.getOriginalBytes()),
                bytes(version.getArchiveBytes()), String.format(Locale.ROOT, "%.1f",
                    version.getDurationMillis() / 1000.0D)),
            width / 2 - 202, top + 16, 0xBBBBBB);
        String state = entry.errorKey == null ? I18n.format("gui.worldbackup.status.recoverable")
            : I18n.format("gui.worldbackup.status.unrecoverable", I18n.format(entry.errorKey));
        fontRenderer.drawString(state, width / 2 - 202, top + 28,
            entry.errorKey == null ? 0x66FF66 : 0xFF6666);
    }

    private int visibleRows() { return Math.max(1, (height - 105) / ROW_HEIGHT); }
    private int itemCount() { return safetyMode ? safetyCopies.size() : entries.size(); }
    private int pageCount() { return Math.max(1, (itemCount() + visibleRows() - 1) / visibleRows()); }
    private Entry selectedEntry() {
        return !safetyMode && selected >= 0 && selected < entries.size()
            ? entries.get(selected) : null;
    }
    private static String bytes(long value) {
        String[] units = { "B", "KiB", "MiB", "GiB", "TiB" };
        double size = value;
        int unit = 0;
        while (size >= 1024D && unit + 1 < units.length) { size /= 1024D; unit++; }
        return unit == 0 ? Long.toString(value) + " " + units[unit]
            : String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }
    static boolean backupDestructiveEnabled(boolean hasSelection,
                                            boolean identityValidated,
                                            boolean busy) {
        return hasSelection && identityValidated && !busy;
    }
    static String errorKey(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof BackupException) {
                return ((BackupException) cursor).getTranslationKey();
            }
            cursor = cursor.getCause();
        }
        return "worldbackup.error.restore_swap";
    }
    private static final class Entry {
        private final BackupVersion version;
        private final String errorKey;
        private Entry(BackupVersion version, String errorKey) {
            this.version = version;
            this.errorKey = errorKey;
        }
    }
}
