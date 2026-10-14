package com.rlcmj.worldbackup.config;

import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupException;
import org.junit.After;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

public class ConfigSnapshotTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void restoreDefaults() {
        WorldBackupConfig.enabled = true;
        WorldBackupConfig.scheduledEnabled = true;
        WorldBackupConfig.worldLoadEnabled = true;
        WorldBackupConfig.exitEnabled = true;
        WorldBackupConfig.mode = BackupMode.INCREMENTAL;
        WorldBackupConfig.intervalMinutes = 20;
        WorldBackupConfig.maxVersions = 10;
        WorldBackupConfig.incrementsPerBase = 5;
        WorldBackupConfig.backupPath = "backups";
    }

    @Test
    public void capturesExactDefaults() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game").toPath();

        ConfigSnapshot snapshot = ConfigSnapshot.capture(gameDir);

        assertTrue(snapshot.isEnabled());
        assertTrue(snapshot.isScheduledEnabled());
        assertTrue(snapshot.isWorldLoadEnabled());
        assertTrue(snapshot.isExitEnabled());
        assertEquals(BackupMode.INCREMENTAL, snapshot.getMode());
        assertEquals(20, snapshot.getIntervalMinutes());
        assertEquals(10, snapshot.getMaxVersions());
        assertEquals(5, snapshot.getIncrementsPerBase());
        assertEquals(gameDir.resolve("backups").toAbsolutePath().normalize(),
            snapshot.getBackupRoot());
    }

    @Test
    public void capturesDisabledWorldLoadSetting() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game-world-load-disabled").toPath();
        WorldBackupConfig.worldLoadEnabled = false;

        ConfigSnapshot snapshot = ConfigSnapshot.capture(gameDir);

        assertFalse(snapshot.isWorldLoadEnabled());
    }

    @Test
    public void clampsAllIntegerSettingsToAtLeastOne() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game").toPath();
        WorldBackupConfig.intervalMinutes = 0;
        WorldBackupConfig.maxVersions = -10;
        WorldBackupConfig.incrementsPerBase = Integer.MIN_VALUE;

        ConfigSnapshot snapshot = ConfigSnapshot.capture(gameDir);

        assertEquals(1, snapshot.getIntervalMinutes());
        assertEquals(1, snapshot.getMaxVersions());
        assertEquals(1, snapshot.getIncrementsPerBase());
    }

    @Test
    public void resolvesRelativePathBelowGameDirectory() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game").toPath();
        WorldBackupConfig.backupPath = "custom/../archives";

        ConfigSnapshot snapshot = ConfigSnapshot.capture(gameDir);

        assertEquals(gameDir.resolve("archives").toAbsolutePath().normalize(),
            snapshot.getBackupRoot());
        WorldBackupConfig.backupPath = "../outside";
        assertThrows(IllegalArgumentException.class,
            () -> ConfigSnapshot.capture(gameDir));
    }

    @Test
    public void normalizesAbsolutePath() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game").toPath();
        Path external = temporaryFolder.newFolder("external").toPath();
        WorldBackupConfig.backupPath = external.resolve("unused/../archives").toString();

        ConfigSnapshot snapshot = ConfigSnapshot.capture(gameDir);

        assertEquals(external.resolve("archives").toAbsolutePath().normalize(),
            snapshot.getBackupRoot());
    }

    @Test
    public void rejectsEitherDirectionOfWorldContainment() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game").toPath();
        Path world = Files.createDirectories(gameDir.resolve("saves/world"));

        WorldBackupConfig.backupPath = world.resolve("backups").toString();
        assertThrows(IllegalArgumentException.class,
            () -> ConfigSnapshot.capture(gameDir, world));

        WorldBackupConfig.backupPath = gameDir.resolve("saves").toString();
        assertThrows(IllegalArgumentException.class,
            () -> ConfigSnapshot.capture(gameDir, world));

        WorldBackupConfig.backupPath = gameDir.resolve("backups").toString();
        ConfigSnapshot safe = ConfigSnapshot.capture(gameDir, world);
        assertFalse(safe.getBackupRoot().startsWith(world));
        safe.validate(world);
    }

    @Test
    public void resolvesExistingSymlinkSegmentsBeforeContainmentChecks() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game").toPath();
        Path world = Files.createDirectories(gameDir.resolve("saves/world"));
        Path link = gameDir.resolve("linked-backups");
        try {
            Files.createSymbolicLink(link, world);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeNoException(unavailable);
        }
        WorldBackupConfig.backupPath = link.resolve("nested").toString();

        assertThrows(IllegalArgumentException.class,
            () -> ConfigSnapshot.capture(gameDir, world));
    }

    @Test
    public void rejectsReservedStagingRootAndDescendants() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game-reserved").toPath();
        Path staging = Files.createDirectories(gameDir.resolve(".worldbackup-staging"));

        for (Path reserved : new Path[] { staging, staging.resolve("snapshot-backups") }) {
            WorldBackupConfig.backupPath = reserved.toString();
            BackupException failure = assertThrows(BackupException.class,
                () -> ConfigSnapshot.capture(gameDir));
            assertEquals("worldbackup.error.backup_path_reserved",
                failure.getTranslationKey());
        }
    }

    @Test
    public void rejectsBackupRootThatContainsReservedStagingRoot() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game-reserved-ancestor").toPath();
        WorldBackupConfig.backupPath = gameDir.toString();

        BackupException failure = assertThrows(BackupException.class,
            () -> ConfigSnapshot.capture(gameDir));

        assertEquals("worldbackup.error.backup_path_reserved",
            failure.getTranslationKey());
    }

    @Test
    public void rejectsReservedStagingAliasThroughExistingSymlink() throws Exception {
        Path gameDir = temporaryFolder.newFolder("game-reserved-link").toPath();
        Path staging = Files.createDirectories(gameDir.resolve(".worldbackup-staging"));
        Path link = gameDir.resolve("staging-alias");
        try {
            Files.createSymbolicLink(link, staging);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeNoException(unavailable);
        }
        WorldBackupConfig.backupPath = link.resolve("snapshot-backups").toString();

        BackupException failure = assertThrows(BackupException.class,
            () -> ConfigSnapshot.capture(gameDir));

        assertEquals("worldbackup.error.backup_path_reserved",
            failure.getTranslationKey());
    }
}
