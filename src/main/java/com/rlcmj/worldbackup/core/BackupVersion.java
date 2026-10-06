package com.rlcmj.worldbackup.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BackupVersion {
    private String id;
    private long createdAt;
    private BackupTrigger trigger;
    private BackupMode mode;
    private String archiveName;
    private String parentId;
    private String baseId;
    private boolean visible;
    private long originalBytes;
    private long archiveBytes;
    private long durationMillis;
    private SnapshotManifest manifest;
    private List<String> changedPaths;
    private List<String> deletedPaths;

    private BackupVersion() { }

    public BackupVersion(String id, long createdAt, BackupTrigger trigger, BackupMode mode,
                         String archiveName, String parentId, String baseId, boolean visible,
                         long originalBytes, long archiveBytes, long durationMillis,
                         SnapshotManifest manifest, Collection<String> changedPaths,
                         Collection<String> deletedPaths) {
        this.id = requireText(id, "id");
        this.createdAt = createdAt;
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.archiveName = requireText(archiveName, "archiveName");
        this.parentId = parentId;
        this.baseId = baseId;
        this.visible = visible;
        this.originalBytes = originalBytes;
        this.archiveBytes = archiveBytes;
        this.durationMillis = durationMillis;
        this.manifest = new SnapshotManifest(Objects.requireNonNull(manifest, "manifest"));
        this.changedPaths = normalizePaths(changedPaths, "changedPaths");
        this.deletedPaths = normalizePaths(deletedPaths, "deletedPaths");
    }

    public static BackupVersion full(String id, long createdAt, BackupTrigger trigger,
                                     String archiveName, long originalBytes, long archiveBytes,
                                     long durationMillis, SnapshotManifest manifest) {
        return new BackupVersion(id, createdAt, trigger, BackupMode.FULL, archiveName,
            null, null, true, originalBytes, archiveBytes, durationMillis, manifest,
            Collections.emptyList(), Collections.emptyList());
    }

    private static List<String> normalizePaths(Collection<String> paths, String name) {
        Objects.requireNonNull(paths, name);
        List<String> normalized = new ArrayList<>(paths.size());
        for (String path : paths) {
            normalized.add(SnapshotManifest.normalizePath(path));
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    public String getId() {
        return id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public BackupTrigger getTrigger() {
        return trigger;
    }

    public BackupMode getMode() {
        return mode;
    }

    public String getArchiveName() {
        return archiveName;
    }

    public String getParentId() {
        return parentId;
    }

    public String getBaseId() {
        return baseId;
    }

    public boolean isVisible() {
        return visible;
    }

    public long getOriginalBytes() {
        return originalBytes;
    }

    public long getArchiveBytes() {
        return archiveBytes;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public SnapshotManifest getManifest() {
        return new SnapshotManifest(manifest);
    }

    public List<String> getChangedPaths() {
        return Collections.unmodifiableList(changedPaths);
    }

    public List<String> getDeletedPaths() {
        return Collections.unmodifiableList(deletedPaths);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BackupVersion)) {
            return false;
        }
        BackupVersion that = (BackupVersion) other;
        return createdAt == that.createdAt
            && visible == that.visible
            && originalBytes == that.originalBytes
            && archiveBytes == that.archiveBytes
            && durationMillis == that.durationMillis
            && id.equals(that.id)
            && trigger == that.trigger
            && mode == that.mode
            && archiveName.equals(that.archiveName)
            && Objects.equals(parentId, that.parentId)
            && Objects.equals(baseId, that.baseId)
            && manifest.equals(that.manifest)
            && changedPaths.equals(that.changedPaths)
            && deletedPaths.equals(that.deletedPaths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, createdAt, trigger, mode, archiveName, parentId, baseId, visible,
            originalBytes, archiveBytes, durationMillis, manifest, changedPaths, deletedPaths);
    }
}
