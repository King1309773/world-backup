package com.rlcmj.worldbackup.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BackupCatalog {
    public static final int FORMAT_VERSION = 1;

    private int formatVersion;
    private String worldId;
    private String displayName;
    private List<BackupVersion> versions;

    private BackupCatalog() { }

    private BackupCatalog(String worldId, String displayName) {
        this.formatVersion = FORMAT_VERSION;
        this.worldId = requireText(worldId, "worldId");
        this.displayName = requireText(displayName, "displayName");
        this.versions = new ArrayList<>();
    }

    public static BackupCatalog empty(String worldId, String displayName) {
        return new BackupCatalog(worldId, displayName);
    }

    public void add(BackupVersion version) {
        versions.add(Objects.requireNonNull(version, "version"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public String getWorldId() {
        return worldId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<BackupVersion> getVersions() {
        return Collections.unmodifiableList(versions);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BackupCatalog)) {
            return false;
        }
        BackupCatalog that = (BackupCatalog) other;
        return formatVersion == that.formatVersion
            && worldId.equals(that.worldId)
            && displayName.equals(that.displayName)
            && versions.equals(that.versions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(formatVersion, worldId, displayName, versions);
    }
}
