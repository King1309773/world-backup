package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import com.rlcmj.worldbackup.storage.CatalogStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BackupManagerServiceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void deletionCommitsCatalogBeforeRemovingOnlyNewlyUnreachableArchive()
            throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        BackupCatalog catalog = BackupCatalog.empty("World1", "World One");
        catalog.add(version("base", BackupMode.FULL, "base.zip", null, null, false));
        catalog.add(version("remove", BackupMode.INCREMENTAL, "remove.zip",
            "base", "base", true));
        catalog.add(version("keep", BackupMode.INCREMENTAL, "keep.zip",
            "base", "base", true));
        for (String archive : new String[] { "base.zip", "remove.zip", "keep.zip" }) {
            Files.write(repo.resolve(archive), new byte[] { 1 });
        }
        new CatalogStore().save(repo, catalog);

        BackupManagerService.DeleteResult result =
            new BackupManagerService().delete(repo, "World1", "remove");

        assertTrue(result.getWarnings().isEmpty());
        BackupCatalog retained = new CatalogStore().load(repo);
        assertEquals(2, retained.getVersions().size());
        assertFalse(Files.exists(repo.resolve("remove.zip")));
        assertTrue(Files.exists(repo.resolve("base.zip")));
        assertTrue(Files.exists(repo.resolve("keep.zip")));
    }

    @Test
    public void mismatchedCatalogIdentityRejectsLoadAndDeleteWithoutChangingRepository()
            throws Exception {
        Path repo = temp.newFolder("mismatched-repo").toPath();
        BackupCatalog catalog = BackupCatalog.empty("OtherWorld", "Other World");
        catalog.add(version("remove", BackupMode.FULL, "remove.zip",
            null, null, true));
        Path archive = repo.resolve("remove.zip");
        Files.write(archive, new byte[] { 1, 2, 3 });
        Path sentinelDirectory = Files.createDirectory(repo.resolve("keep-directory"));
        Path sentinelFile = Files.write(sentinelDirectory.resolve("keep.dat"),
            new byte[] { 4 });
        new CatalogStore().save(repo, catalog);
        byte[] originalIndex = Files.readAllBytes(repo.resolve("index.json"));

        BackupManagerService service = new BackupManagerService();
        com.rlcmj.worldbackup.core.BackupException loadFailure = assertThrows(
            com.rlcmj.worldbackup.core.BackupException.class,
            () -> service.load(repo, "SelectedWorld"));
        assertEquals("worldbackup.error.catalog_mismatch",
            loadFailure.getTranslationKey());

        com.rlcmj.worldbackup.core.BackupException deleteFailure = assertThrows(
            com.rlcmj.worldbackup.core.BackupException.class,
            () -> service.delete(repo, "SelectedWorld", "remove"));
        assertEquals("worldbackup.error.catalog_mismatch",
            deleteFailure.getTranslationKey());
        assertTrue(Files.exists(archive));
        assertTrue(Files.exists(sentinelDirectory));
        assertTrue(Files.exists(sentinelFile));
        assertTrue(Arrays.equals(originalIndex,
            Files.readAllBytes(repo.resolve("index.json"))));
        assertEquals(catalog, new CatalogStore().load(repo));
    }

    @Test
    public void backupDestructiveStateRequiresValidatedCatalogIdentity() {
        assertFalse(GuiBackupManager.backupDestructiveEnabled(false, true, false, true));
        assertFalse(GuiBackupManager.backupDestructiveEnabled(true, false, false, true));
        assertTrue(GuiBackupManager.backupDestructiveEnabled(true, true, false, true));
        assertFalse(GuiBackupManager.backupDestructiveEnabled(true, true, true, true));
        assertFalse(GuiBackupManager.backupDestructiveEnabled(true, true, false, false));
    }

    @Test
    public void catalogLoadDoesNotReadOrHashArchivePayloads() throws Exception {
        Path repo = temp.newFolder("catalog-only-load").toPath();
        BackupCatalog catalog = BackupCatalog.empty("World1", "World One");
        catalog.add(version("visible", BackupMode.FULL, "missing.zip",
            null, null, true));
        new CatalogStore().save(repo, catalog);

        BackupCatalog loaded = new BackupManagerService().load(repo, "World1");

        assertEquals(catalog, loaded);
        assertFalse(Files.exists(repo.resolve("missing.zip")));
    }

    private static BackupVersion version(String id, BackupMode mode, String archive,
                                         String parent, String base, boolean visible) {
        return new BackupVersion(id, 1L, BackupTrigger.MANUAL, mode, archive,
            parent, base, visible, 1L, 1L, 1L, new SnapshotManifest(),
            Collections.emptyList(), Collections.emptyList());
    }
}
