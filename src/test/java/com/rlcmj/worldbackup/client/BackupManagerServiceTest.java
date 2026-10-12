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
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
            new BackupManagerService().delete(repo, "remove");

        assertTrue(result.getWarnings().isEmpty());
        BackupCatalog retained = new CatalogStore().load(repo);
        assertEquals(2, retained.getVersions().size());
        assertFalse(Files.exists(repo.resolve("remove.zip")));
        assertTrue(Files.exists(repo.resolve("base.zip")));
        assertTrue(Files.exists(repo.resolve("keep.zip")));
    }

    private static BackupVersion version(String id, BackupMode mode, String archive,
                                         String parent, String base, boolean visible) {
        return new BackupVersion(id, 1L, BackupTrigger.MANUAL, mode, archive,
            parent, base, visible, 1L, 1L, 1L, new SnapshotManifest(),
            Collections.emptyList(), Collections.emptyList());
    }
}
