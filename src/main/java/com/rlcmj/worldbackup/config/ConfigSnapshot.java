package com.rlcmj.worldbackup.config;

import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.PathRelations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ConfigSnapshot {
    private final boolean enabled;
    private final boolean scheduledEnabled;
    private final boolean worldLoadEnabled;
    private final boolean exitEnabled;
    private final BackupMode mode;
    private final int intervalMinutes;
    private final int maxVersions;
    private final int incrementsPerBase;
    private final Path backupRoot;

    private ConfigSnapshot(boolean enabled, boolean scheduledEnabled,
            boolean worldLoadEnabled, boolean exitEnabled, BackupMode mode,
            int intervalMinutes, int maxVersions, int incrementsPerBase,
            Path backupRoot) {
        this.enabled = enabled;
        this.scheduledEnabled = scheduledEnabled;
        this.worldLoadEnabled = worldLoadEnabled;
        this.exitEnabled = exitEnabled;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.intervalMinutes = intervalMinutes;
        this.maxVersions = maxVersions;
        this.incrementsPerBase = incrementsPerBase;
        this.backupRoot = Objects.requireNonNull(backupRoot, "backupRoot");
    }

    public static ConfigSnapshot capture(Path gameDir) throws BackupException {
        return capture(gameDir, null);
    }

    public static ConfigSnapshot capture(Path gameDir, Path activeWorldRoot)
            throws BackupException {
        Path normalizedGameDir = absolute(gameDir, "gameDir");
        String configuredPath = WorldBackupConfig.backupPath;
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            throw new IllegalArgumentException("backupPath must not be blank");
        }

        Path requested;
        try {
            requested = Paths.get(configuredPath.trim());
        } catch (RuntimeException invalidPath) {
            throw new IllegalArgumentException("backupPath is invalid", invalidPath);
        }
        boolean relative = !requested.isAbsolute();
        Path backupRoot = (relative ? normalizedGameDir.resolve(requested) : requested)
            .toAbsolutePath().normalize();
        if (relative && !backupRoot.startsWith(normalizedGameDir)) {
            throw new IllegalArgumentException("relative backupPath must remain below gameDir");
        }
        if (relative && !resolveExistingSegments(backupRoot)
                .startsWith(resolveExistingSegments(normalizedGameDir))) {
            throw new IllegalArgumentException("relative backupPath must remain below gameDir");
        }
        Path reservedStaging = normalizedGameDir.resolve(".worldbackup-staging");
        try {
            if (PathRelations.overlaps(backupRoot, reservedStaging)) {
                throw new BackupException("worldbackup.error.backup_path_reserved",
                    backupRoot.toString());
            }
        } catch (IOException | RuntimeException invalidPath) {
            throw new BackupException("worldbackup.error.backup_path_reserved",
                invalidPath, backupRoot.toString());
        }

        ConfigSnapshot snapshot = new ConfigSnapshot(
            WorldBackupConfig.enabled,
            WorldBackupConfig.scheduledEnabled,
            WorldBackupConfig.worldLoadEnabled,
            WorldBackupConfig.exitEnabled,
            WorldBackupConfig.mode,
            Math.max(1, WorldBackupConfig.intervalMinutes),
            Math.max(1, WorldBackupConfig.maxVersions),
            Math.max(1, WorldBackupConfig.incrementsPerBase),
            backupRoot);
        if (activeWorldRoot != null) {
            snapshot.validate(activeWorldRoot);
        }
        return snapshot;
    }

    public void validate(Path activeWorldRoot) {
        validate(backupRoot, activeWorldRoot);
    }

    public static void validate(Path backupRoot, Path activeWorldRoot) {
        Path resolvedBackup = resolveExistingSegments(
            absolute(backupRoot, "backupRoot"));
        Path resolvedWorld = resolveExistingSegments(
            absolute(activeWorldRoot, "activeWorldRoot"));
        if (resolvedBackup.startsWith(resolvedWorld)
                || resolvedWorld.startsWith(resolvedBackup)) {
            throw new IllegalArgumentException(
                "backup root and active world root must not contain each other");
        }
    }

    private static Path absolute(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static Path resolveExistingSegments(Path path) {
        Path cursor = path;
        List<Path> missing = new ArrayList<Path>();
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            Path name = cursor.getFileName();
            if (name != null) {
                missing.add(name);
            }
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return path;
        }
        try {
            Path resolved = cursor.toRealPath();
            Collections.reverse(missing);
            for (Path name : missing) {
                resolved = resolved.resolve(name);
            }
            return resolved.normalize();
        } catch (IOException | RuntimeException invalidPath) {
            throw new IllegalArgumentException("unable to validate path: " + path,
                invalidPath);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isScheduledEnabled() {
        return scheduledEnabled;
    }

    public boolean isWorldLoadEnabled() {
        return worldLoadEnabled;
    }

    public boolean isExitEnabled() {
        return exitEnabled;
    }

    public BackupMode getMode() {
        return mode;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public int getMaxVersions() {
        return maxVersions;
    }

    public int getIncrementsPerBase() {
        return incrementsPerBase;
    }

    public Path getBackupRoot() {
        return backupRoot;
    }
}
