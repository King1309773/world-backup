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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    // One serialized daemon for the client process. Screens cancel their Future on
    // resize/close; the daemon itself terminates with the Minecraft JVM.
    private static final ExecutorService VALIDATION_EXECUTOR =
        Executors.newSingleThreadExecutor(command -> {
            Thread thread = new Thread(command, "World Backup UI Validation");
            thread.setDaemon(true);
            return thread;
        });

    private final GuiScreen parent;
    private final String worldFolder;
    private final BackupCoordinator coordinator;
    private final BackupManagerService manager = new BackupManagerService();
    private final BackupRestorer restorer = new BackupRestorer();
    private final SafetyCopyStore safetyStore = new SafetyCopyStore();
    private final BackupManagerLoadController<LoadResult> loadController =
        new BackupManagerLoadController<>(VALIDATION_EXECUTOR,
            command -> { mc.addScheduledTask(command); });
    private final List<Entry> entries = new ArrayList<>();
    private final List<Path> safetyCopies = new ArrayList<>();
    private Path repository;
    private final BackupManagerViewState viewState = new BackupManagerViewState();
    private boolean safetyMode;
    private boolean catalogIdentityValidated;
    private String statusKey;
    private Object[] statusArguments = new Object[0];
    private boolean screenActive;

    public GuiBackupManager(GuiScreen parent, String worldFolder,
                            BackupCoordinator coordinator) {
        this.parent = parent;
        this.worldFolder = worldFolder;
        this.coordinator = coordinator;
    }

    @Override public void initGui() {
        screenActive = true;
        buttonList.clear();
        boolean compact = width < 432;
        int gap = 4;
        int buttonWidth = compact ? Math.max(70, (width - 24 - gap) / 2) : 100;
        int left = compact ? 10 : width / 2 - 204;
        int firstY = compact ? height - 52 : height - 28;
        buttonList.add(new GuiButton(RESTORE, left, firstY, buttonWidth, 20,
            I18n.format("gui.worldbackup.restore")));
        buttonList.add(new GuiButton(DELETE, left + buttonWidth + gap, firstY,
            buttonWidth, 20,
            safetyMode ? I18n.format("gui.worldbackup.delete_safety")
                : I18n.format("gui.worldbackup.delete")));
        int secondY = height - 28;
        int thirdX = compact ? left : width / 2 + 4;
        int fourthX = compact ? left + buttonWidth + gap : width / 2 + 108;
        buttonList.add(new GuiButton(SAFETY, thirdX, secondY, buttonWidth, 20,
            safetyMode ? I18n.format("gui.worldbackup.backups")
                : I18n.format("gui.worldbackup.safety_copies")));
        buttonList.add(new GuiButton(DONE, fourthX, secondY,
            compact ? buttonWidth : 96, 20,
            I18n.format("gui.worldbackup.done")));
        int pagerY = compact ? height - 76 : height - 52;
        buttonList.add(new GuiButton(PREVIOUS, width / 2 - 45, pagerY, 40, 20, "<"));
        buttonList.add(new GuiButton(NEXT, width / 2 + 5, pagerY, 40, 20, ">"));
        if (!loadController.isLoaded() && !loadController.isLoading()) {
            startLoad(repository != null, null, new Object[0]);
        }
        updateButtons();
    }

    public void reload() {
        startLoad(false, null, new Object[0]);
    }

    private void startLoad(boolean preserveView, String completedStatus,
                           Object[] completedArguments) {
        int previousPage = viewState.getPage();
        int previousSelection = viewState.getSelection();
        stopValidation();
        entries.clear();
        safetyCopies.clear();
        viewState.select(preserveView ? previousSelection : -1);
        viewState.setPage(preserveView ? previousPage : 0);
        statusKey = "gui.worldbackup.status.loading";
        statusArguments = new Object[0];
        catalogIdentityValidated = false;
        final boolean requestedSafetyMode = safetyMode;
        final Path game = mc.gameDir.toPath();
        loadController.start(cancellation -> {
                Path loadedRepository = ConfigSnapshot.capture(game).getBackupRoot()
                    .resolve(worldFolder);
                if (requestedSafetyMode) {
                    return LoadResult.safety(loadedRepository,
                        safetyStore.list(game, worldFolder));
                } else if (!Files.isRegularFile(loadedRepository.resolve("index.json"),
                        LinkOption.NOFOLLOW_LINKS)) {
                    return LoadResult.backups(loadedRepository,
                        new ArrayList<BackupVersion>(), null);
                }
                BackupCatalog catalog = manager.load(loadedRepository, worldFolder,
                    cancellation);
                BackupRestorer.AvailabilityReport report =
                    restorer.validateAvailabilityBatch(loadedRepository, worldFolder,
                        catalog, cancellation);
                return LoadResult.backups(loadedRepository, catalog.getVersions(), report);
            }, new BackupManagerLoadController.Completion<LoadResult>() {
                @Override public void succeeded(LoadResult result) {
                    if (!screenActive || requestedSafetyMode != safetyMode) return;
                    statusKey = completedStatus;
                    statusArguments = completedArguments;
                    repository = result.repository;
                    safetyCopies.addAll(result.safetyCopies);
                    for (BackupVersion version : result.versions) {
                        if (!version.isVisible()) continue;
                        Entry entry = new Entry(version);
                        entry.validating = false;
                        entry.errorKey = result.report == null ? null
                            : result.report.getErrorKey(version.getId());
                        entries.add(entry);
                    }
                    entries.sort(Comparator.comparingLong(
                        (Entry value) -> value.version.getCreatedAt()).reversed());
                    catalogIdentityValidated = !requestedSafetyMode;
                    updateButtons();
                }

                @Override public void failed(Throwable failure) {
                    if (!screenActive || requestedSafetyMode != safetyMode) return;
                    statusKey = errorKey(failure);
                    statusArguments = new Object[0];
                    updateButtons();
                }
            });
        updateButtons();
    }

    private void stopValidation() {
        loadController.cancel();
    }

    @Override public void onGuiClosed() {
        screenActive = false;
        stopValidation();
        super.onGuiClosed();
    }

    @Override public void updateScreen() {
        updateButtons();
    }

    private void updateButtons() {
        viewState.reconcile(itemCount(), visibleRows());
        Entry entry = selectedEntry();
        boolean worldClosed = mc != null && mc.world == null
            && mc.getIntegratedServer() == null;
        find(RESTORE).visible = !safetyMode;
        find(RESTORE).enabled = !safetyMode && entry != null && !entry.validating
            && entry.errorKey == null
            && loadController.isLoaded() && catalogIdentityValidated
            && worldClosed && !coordinator.isBusy();
        find(DELETE).enabled = safetyMode
            ? loadController.isLoaded() && viewState.getSelection() >= 0
                && !coordinator.isBusy()
            : backupDestructiveEnabled(viewState.getSelection() >= 0,
                catalogIdentityValidated, coordinator.isBusy(), loadController.isLoaded());
        find(DONE).enabled = !coordinator.isRestoreActive();
        int pages = pageCount();
        find(PREVIOUS).enabled = viewState.getPage() > 0;
        find(NEXT).enabled = viewState.getPage() + 1 < pages;
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
            loadController.cancel();
            initGui();
        } else if (button.id == PREVIOUS) {
            viewState.setPage(viewState.getPage() - 1);
            viewState.select(-1);
        } else if (button.id == NEXT) {
            viewState.setPage(viewState.getPage() + 1);
            viewState.select(-1);
        } else if (button.id == RESTORE && selectedEntry() != null) {
            mc.displayGuiScreen(new GuiRestoreConfirm(this, worldFolder, repository,
                selectedEntry().version, coordinator));
        } else if (button.id == DELETE && viewState.getSelection() >= 0) {
            String name = safetyMode ? safetyCopies.get(viewState.getSelection()).getFileName().toString()
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
        Entry confirmedEntry = selectedEntry();
        Path confirmedRepository = repository;
        String confirmedSafetyCopy = id == CONFIRM_SAFETY_DELETE
            && viewState.getSelection() >= 0
            && viewState.getSelection() < safetyCopies.size()
                ? safetyCopies.get(viewState.getSelection()).getFileName().toString()
                : null;
        mc.displayGuiScreen(this);
        if (!result || coordinator.isBusy()) return;
        if (id == CONFIRM_BACKUP_DELETE && confirmedEntry != null) {
            final String versionId = confirmedEntry.version.getId();
            final Path deleteRepository = confirmedRepository;
            beginMaintenance();
            if (!coordinator.requestMaintenance(() -> manager.delete(deleteRepository,
                    worldFolder, versionId), deleteCallback())) {
                startLoad(false, "worldbackup.message.busy", new Object[0]);
            }
        } else if (id == CONFIRM_SAFETY_DELETE && confirmedSafetyCopy != null) {
            final Path game = mc.gameDir.toPath();
            final String copyName = confirmedSafetyCopy;
            beginMaintenance();
            if (!coordinator.requestMaintenance(() -> {
                safetyStore.delete(game, worldFolder, copyName);
                return new Object();
            }, deleteCallback())) {
                startLoad(false, "worldbackup.message.busy", new Object[0]);
            }
        }
    }

    private void beginMaintenance() {
        stopValidation();
        setStatus("gui.worldbackup.status.deleting");
        updateButtons();
    }

    private BackupCoordinator.MaintenanceCallback<Object> deleteCallback() {
        return new BackupCoordinator.MaintenanceCallback<Object>() {
            @Override public void succeeded(Object value) {
                if (!screenActive) return;
                if (value instanceof BackupManagerService.DeleteResult
                        && !((BackupManagerService.DeleteResult) value).getWarnings().isEmpty()) {
                    startLoad(false, "gui.worldbackup.status.delete_warning",
                        new Object[] { ((BackupManagerService.DeleteResult) value)
                            .getWarnings().size() });
                } else {
                    startLoad(false, "gui.worldbackup.status.delete_success", new Object[0]);
                }
            }
            @Override public void failed(Throwable failure) {
                if (!screenActive) return;
                setStatus(errorKey(failure));
                updateButtons();
            }
        };
    }

    void beginRestore() {
        setStatus("gui.worldbackup.status.restoring");
        updateButtons();
    }

    void restoreSucceeded() {
        mc.getSaveLoader().flushCache();
        startLoad(false, "worldbackup.message.restore_success", new Object[0]);
    }

    void restoreFailed(Throwable failure) {
        startLoad(true, "worldbackup.message.restore_failure",
            new Object[] { I18n.format(errorKey(failure)) });
    }

    void setStatus(String key, Object... arguments) {
        statusKey = key;
        statusArguments = arguments;
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (!coordinator.isRestoreActive()) super.keyTyped(typedChar, keyCode);
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        int firstY = 34;
        int visibleRows = visibleRows();
        int row = (mouseY - firstY) / ROW_HEIGHT;
        int left = contentLeft();
        int right = contentRight();
        if (BackupManagerViewState.isRowHit(mouseX, mouseY, firstY, ROW_HEIGHT,
                left, right) && row >= 0 && row < visibleRows) {
            int index = viewState.getPage() * visibleRows + row;
            if (index < itemCount()) viewState.select(index);
        }
        updateButtons();
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, fit(I18n.format(safetyMode
                ? "gui.worldbackup.safety_title" : "gui.worldbackup.title", worldFolder)),
            width / 2, 12, 0xFFFFFF);
        int rows = visibleRows();
        int start = viewState.getPage() * rows;
        for (int row = 0; row < rows && start + row < itemCount(); row++) {
            int index = start + row;
            int top = 34 + row * ROW_HEIGHT;
            drawRect(contentLeft(), top, contentRight(), top + 39,
                index == viewState.getSelection() ? 0xAA506080 : 0x88404040);
            if (safetyMode) drawSafety(safetyCopies.get(index), top);
            else drawEntry(entries.get(index), top);
        }
        if (itemCount() == 0 && statusKey == null) {
            drawCenteredString(fontRenderer, I18n.format("gui.worldbackup.status.empty"),
                width / 2, 55, 0xAAAAAA);
        }
        if (statusKey != null) {
            drawCenteredString(fontRenderer, fit(I18n.format(statusKey, statusArguments)),
                width / 2, height - (width < 432 ? 88 : 64), 0xFF7777);
        }
        updateButtons();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawSafety(Path copy, int top) {
        fontRenderer.drawString(fitRow(copy.getFileName().toString()), contentLeft() + 8,
            top + 8, 0xFFFFFF);
        fontRenderer.drawString(fitRow(I18n.format("gui.worldbackup.safety_hint")),
            contentLeft() + 8, top + 23, 0xAAAAAA);
    }

    private void drawEntry(Entry entry, int top) {
        BackupVersion version = entry.version;
        String time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM,
            Locale.getDefault()).format(new Date(version.getCreatedAt()));
        fontRenderer.drawString(fitRow(time + "  "
                + I18n.format("worldbackup.trigger." + version.getTrigger().name().toLowerCase(Locale.ROOT))
                + " / " + I18n.format("worldbackup.mode." + version.getMode().name().toLowerCase(Locale.ROOT))),
            contentLeft() + 8, top + 4, 0xFFFFFF);
        fontRenderer.drawString(fitRow(I18n.format("gui.worldbackup.details", bytes(version.getOriginalBytes()),
                bytes(version.getArchiveBytes()), String.format(Locale.ROOT, "%.1f",
                    version.getDurationMillis() / 1000.0D))),
            contentLeft() + 8, top + 16, 0xBBBBBB);
        String state = entry.validating ? I18n.format("gui.worldbackup.status.validating")
            : entry.errorKey == null ? I18n.format("gui.worldbackup.status.recoverable")
            : I18n.format("gui.worldbackup.status.unrecoverable", I18n.format(entry.errorKey));
        fontRenderer.drawString(fitRow(state), contentLeft() + 8, top + 28,
            entry.validating ? 0xFFCC66 : entry.errorKey == null ? 0x66FF66 : 0xFF6666);
    }

    private int visibleRows() {
        return Math.max(1, (height - (width < 432 ? 129 : 105)) / ROW_HEIGHT);
    }
    private int contentLeft() { return Math.max(8, width / 2 - 210); }
    private int contentRight() { return Math.min(width - 8, width / 2 + 210); }
    private String fit(String text) {
        return fontRenderer.trimStringToWidth(text, Math.max(1, width - 16));
    }
    private String fitRow(String text) {
        return fontRenderer.trimStringToWidth(text,
            Math.max(1, contentRight() - contentLeft() - 16));
    }
    private int itemCount() { return safetyMode ? safetyCopies.size() : entries.size(); }
    private int pageCount() { return Math.max(1, (itemCount() + visibleRows() - 1) / visibleRows()); }
    private Entry selectedEntry() {
        int selected = viewState.getSelection();
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
                                            boolean busy, boolean loaded) {
        return loaded && hasSelection && identityValidated && !busy;
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
        private String errorKey;
        private boolean validating = true;
        private Entry(BackupVersion version) {
            this.version = version;
        }
    }

    private static final class LoadResult {
        private final Path repository;
        private final List<BackupVersion> versions;
        private final List<Path> safetyCopies;
        private final BackupRestorer.AvailabilityReport report;
        private LoadResult(Path repository, List<BackupVersion> versions,
                           List<Path> safetyCopies,
                           BackupRestorer.AvailabilityReport report) {
            this.repository = repository;
            this.versions = versions;
            this.safetyCopies = safetyCopies;
            this.report = report;
        }
        private static LoadResult backups(Path repository, List<BackupVersion> versions,
                                          BackupRestorer.AvailabilityReport report) {
            return new LoadResult(repository, new ArrayList<>(versions),
                new ArrayList<Path>(), report);
        }
        private static LoadResult safety(Path repository, List<Path> copies) {
            return new LoadResult(repository, new ArrayList<BackupVersion>(),
                new ArrayList<>(copies), null);
        }
    }
}
