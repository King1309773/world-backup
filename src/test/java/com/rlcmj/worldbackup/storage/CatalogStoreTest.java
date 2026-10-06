package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupResult;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CatalogStoreTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripsCatalogAtomically() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        BackupCatalog catalog = BackupCatalog.empty("World1", "My World");
        SnapshotManifest manifest = new SnapshotManifest();
        manifest.put("level.dat", new FileFingerprint(12L, 34L, "abc"));
        catalog.add(BackupVersion.full("v1", 1000L, BackupTrigger.MANUAL,
            "v1-full.zip", 50L, 30L, 1234L, manifest));

        new CatalogStore().save(repo, catalog);
        BackupCatalog loaded = new CatalogStore().load(repo);

        assertEquals(catalog, loaded);
        assertFalse(Files.exists(repo.resolve("index.json.tmp")));
    }

    @Test
    public void rejectsMalformedJsonAsCorruptCatalog() throws Exception {
        Path repo = temp.newFolder("malformed").toPath();
        Files.write(repo.resolve("index.json"), "{broken".getBytes(StandardCharsets.UTF_8));

        BackupException error = assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo));

        assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
    }

    @Test
    public void rejectsUnsupportedCatalogVersion() throws Exception {
        Path repo = temp.newFolder("unsupported").toPath();
        Files.write(repo.resolve("index.json"),
            "{\"formatVersion\":2}".getBytes(StandardCharsets.UTF_8));

        BackupException error = assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo));

        assertEquals("worldbackup.error.catalog_version", error.getTranslationKey());
        assertEquals(2, error.getArguments()[0]);
    }

    @Test
    public void rejectsNullRequiredCatalogValues() throws Exception {
        Path repo = temp.newFolder("null-value").toPath();
        Files.write(repo.resolve("index.json"), (
            "{\"formatVersion\":1,\"worldId\":null,\"displayName\":\"World\",\"versions\":[]}")
            .getBytes(StandardCharsets.UTF_8));

        BackupException error = assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo));

        assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
    }

    @Test
    public void rejectsMissingNestedManifestStructure() throws Exception {
        Path repo = temp.newFolder("missing-manifest-files").toPath();
        String json = "{\"formatVersion\":1,\"worldId\":\"World1\","
            + "\"displayName\":\"World\",\"versions\":[{"
            + "\"id\":\"v1\",\"createdAt\":1,\"trigger\":\"MANUAL\",\"mode\":\"FULL\","
            + "\"archiveName\":\"v1.zip\",\"visible\":true,\"originalBytes\":1,"
            + "\"archiveBytes\":1,\"durationMillis\":1,\"manifest\":{},"
            + "\"changedPaths\":[],\"deletedPaths\":[]}]}";
        Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

        BackupException error = assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo));

        assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
    }

    @Test
    public void atomicWriterFailurePreservesOldTargetAndRemovesTemp() throws Exception {
        Path directory = temp.newFolder("atomic").toPath();
        Path target = directory.resolve("index.json");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> AtomicFiles.write(target, output -> {
            output.write("new".getBytes(StandardCharsets.UTF_8));
            throw new IOException("simulated writer failure");
        }));

        assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(directory.resolve("index.json.tmp")));
    }

    @Test
    public void cancellationThrowsWhenExplicitlyCancelled() {
        BackupCancellation cancellation = new BackupCancellation();
        assertFalse(cancellation.isCancelled());

        cancellation.cancel();

        assertTrue(cancellation.isCancelled());
        assertThrows(InterruptedIOException.class, cancellation::check);
    }

    @Test
    public void cancellationPreservesThreadInterruptStatus() {
        BackupCancellation cancellation = new BackupCancellation();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, cancellation::check);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void manifestNormalizesPathsAndKeepsInsertionOrder() {
        SnapshotManifest manifest = new SnapshotManifest();
        FileFingerprint first = new FileFingerprint(1L, 2L, "one");
        FileFingerprint second = new FileFingerprint(3L, 4L, "two");

        manifest.put("z.dat", first);
        manifest.put("data\\.\\level.dat", second);

        assertEquals(Arrays.asList("z.dat", "data/level.dat"),
            new ArrayList<>(manifest.getFiles().keySet()));
        assertEquals(second, manifest.get("data/level.dat"));
    }

    @Test
    public void manifestRejectsUnsafePaths() {
        SnapshotManifest manifest = new SnapshotManifest();
        FileFingerprint fingerprint = new FileFingerprint(1L, 2L, "hash");

        for (String path : Arrays.asList("../level.dat", "data/../level.dat", "/level.dat",
            "C:level.dat", "C:/level.dat", "C:\\level.dat", "\\\\server\\level.dat")) {
            assertThrows("Expected unsafe path rejection for " + path,
                IllegalArgumentException.class, () -> manifest.put(path, fingerprint));
        }
        assertTrue(manifest.getFiles().isEmpty());
    }

    @Test
    public void fullVersionHasExpectedDefaults() {
        SnapshotManifest manifest = new SnapshotManifest();

        BackupVersion version = BackupVersion.full("v1", 1000L, BackupTrigger.EXIT,
            "v1.zip", 50L, 30L, 25L, manifest);

        assertEquals("v1", version.getId());
        assertEquals(com.rlcmj.worldbackup.core.BackupMode.FULL, version.getMode());
        assertNull(version.getParentId());
        assertNull(version.getBaseId());
        assertTrue(version.isVisible());
        assertTrue(version.getChangedPaths().isEmpty());
        assertTrue(version.getDeletedPaths().isEmpty());
    }

    @Test
    public void backupResultHasValueSemanticsForOutcomes() {
        BackupResult first = BackupResult.success("v1", 25L, 50L, 30L);
        BackupResult second = BackupResult.success("v1", 25L, 50L, 30L);
        BackupResult failure = BackupResult.failure("worldbackup.error.failed", 12L, 5L, 0L);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.isSuccess());
        assertEquals("v1", first.getVersionId());
        assertFalse(failure.isSuccess());
        assertNull(failure.getVersionId());
        assertEquals("worldbackup.error.failed", failure.getErrorKey());
    }

    @Test
    public void backupExceptionPreservesCauseAndArguments() {
        IOException cause = new IOException("disk failure");
        BackupException error = new BackupException("worldbackup.error.failed", cause,
            "World1", 3);

        assertEquals("worldbackup.error.failed", error.getTranslationKey());
        assertEquals(Arrays.asList("World1", 3), Arrays.asList(error.getArguments()));
        assertSame(cause, error.getCause());
    }
}
