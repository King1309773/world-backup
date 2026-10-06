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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
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
    public void rejectsNonIntegerOrNonNumericFormatVersionsAsCorrupt() throws Exception {
        Path repo = temp.newFolder("invalid-format-type").toPath();
        for (String value : Arrays.asList("1.5", "\"1\"", "true", "null")) {
            String json = "{\"formatVersion\":" + value
                + ",\"worldId\":\"World1\",\"displayName\":\"World\",\"versions\":[]}";
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupException error = assertThrows("Expected corrupt formatVersion for " + value,
                BackupException.class, () -> new CatalogStore().load(repo));

            assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        }
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
    public void rejectsWrongJsonTypesBeforeGsonCanCoerceThem() throws Exception {
        Path repo = temp.newFolder("wrong-json-types").toPath();
        String valid = fullyPopulatedCatalogJson();
        for (String json : Arrays.asList(
            valid.replace("\"worldId\":\"World1\"", "\"worldId\":7"),
            valid.replace("\"displayName\":\"World\"", "\"displayName\":8"),
            valid.replace("\"id\":\"v1\"", "\"id\":9"),
            valid.replace("\"archiveName\":\"v1.zip\"", "\"archiveName\":10"),
            valid.replace("\"createdAt\":1", "\"createdAt\":\"1\""),
            valid.replace("\"originalBytes\":2", "\"originalBytes\":\"2\""),
            valid.replace("\"archiveBytes\":3", "\"archiveBytes\":\"3\""),
            valid.replace("\"durationMillis\":4", "\"durationMillis\":\"4\""),
            valid.replace("\"visible\":true", "\"visible\":\"true\""),
            valid.replace("\"parentId\":null", "\"parentId\":11"),
            valid.replace("\"baseId\":null", "\"baseId\":12"),
            valid.replace("\"trigger\":\"MANUAL\"", "\"trigger\":\"UNKNOWN\""),
            valid.replace("\"mode\":\"FULL\"", "\"mode\":\"UNKNOWN\""),
            valid.replace("\"manifest\":{\"files\":{\"level.dat\":{\"size\":12,"
                + "\"modifiedTimeMillis\":34,\"sha256\":\"abc\"}}}", "\"manifest\":[]"),
            valid.replace("\"files\":{\"level.dat\":{\"size\":12,"
                + "\"modifiedTimeMillis\":34,\"sha256\":\"abc\"}}", "\"files\":[]"),
            valid.replace("\"size\":12", "\"size\":\"12\""),
            valid.replace("\"modifiedTimeMillis\":34", "\"modifiedTimeMillis\":\"34\""),
            valid.replace("\"sha256\":\"abc\"", "\"sha256\":99"),
            valid.replace("\"changedPaths\":[\"level.dat\"]", "\"changedPaths\":[13]"),
            valid.replace("\"deletedPaths\":[\"old.dat\"]", "\"deletedPaths\":[false]")
        )) {
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupException error = assertThrows(BackupException.class,
                () -> new CatalogStore().load(repo));

            assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        }
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
    public void failedNonAtomicFallbackRestoresOldTargetAndCleansArtifacts() throws Exception {
        Path directory = temp.newFolder("fallback-rollback").toPath();
        Path target = directory.resolve("index.json");
        Path temporary = directory.resolve("index.json.tmp");
        Path backup = directory.resolve("index.json.bak");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));

        AtomicFiles.FileOperations destructiveFallback = destructiveFallback(temporary, target);
        assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)),
            destructiveFallback));

        assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(backup));
    }

    @Test
    public void failedNonAtomicFallbackLeavesNewTargetAbsent() throws Exception {
        Path directory = temp.newFolder("fallback-new-file").toPath();
        Path target = directory.resolve("index.json");
        Path temporary = directory.resolve("index.json.tmp");
        Path backup = directory.resolve("index.json.bak");
        Files.write(backup, "stale".getBytes(StandardCharsets.UTF_8));

        AtomicFiles.FileOperations destructiveFallback = destructiveFallback(temporary, target);
        assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)),
            destructiveFallback));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(backup));
    }

    @Test
    public void backupCleanupFailureRollsBackSuccessfulFallback() throws Exception {
        Path directory = temp.newFolder("fallback-cleanup-failure").toPath();
        Path target = directory.resolve("index.json");
        Path backup = directory.resolve("index.json.bak");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
        int[] backupDeletes = {0};

        AtomicFiles.FileOperations cleanupFailure = new AtomicFiles.FileOperations() {
            @Override
            public void move(Path source, Path destination, CopyOption... options)
                    throws IOException {
                if (Arrays.asList(options).contains(java.nio.file.StandardCopyOption.ATOMIC_MOVE)) {
                    throw new AtomicMoveNotSupportedException(source.toString(),
                        destination.toString(), "simulated");
                }
                Files.move(source, destination, options);
            }

            @Override
            public void copy(Path source, Path destination, CopyOption... options)
                    throws IOException {
                Files.copy(source, destination, options);
            }

            @Override
            public boolean exists(Path path) {
                return Files.exists(path);
            }

            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                if (path.equals(backup) && ++backupDeletes[0] == 2) {
                    throw new IOException("simulated backup cleanup failure");
                }
                return Files.deleteIfExists(path);
            }
        };

        IOException failure = assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)), cleanupFailure));

        assertEquals("simulated backup cleanup failure", failure.getMessage());
        assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(directory.resolve("index.json.tmp")));
        assertFalse(Files.exists(backup));
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
    public void versionDefensivelyCopiesManifestAtConstructionAndAccess() {
        SnapshotManifest source = new SnapshotManifest();
        source.put("level.dat", new FileFingerprint(1L, 2L, "one"));
        BackupVersion version = BackupVersion.full("v1", 1000L, BackupTrigger.MANUAL,
            "v1.zip", 1L, 1L, 1L, source);

        SnapshotManifest expectedManifest = new SnapshotManifest();
        expectedManifest.put("level.dat", new FileFingerprint(1L, 2L, "one"));
        BackupVersion expected = BackupVersion.full("v1", 1000L, BackupTrigger.MANUAL,
            "v1.zip", 1L, 1L, 1L, expectedManifest);
        int expectedHash = expected.hashCode();

        source.put("from-source.dat", new FileFingerprint(3L, 4L, "two"));
        assertEquals(expected, version);
        assertEquals(expectedHash, version.hashCode());

        version.getManifest().put("from-getter.dat", new FileFingerprint(5L, 6L, "three"));
        assertEquals(expected, version);
        assertEquals(expectedHash, version.hashCode());
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

    private static AtomicFiles.FileOperations destructiveFallback(Path temporary, Path target) {
        return new AtomicFiles.FileOperations() {
            @Override
            public void move(Path source, Path destination, CopyOption... options)
                    throws IOException {
                if (Arrays.asList(options).contains(java.nio.file.StandardCopyOption.ATOMIC_MOVE)) {
                    throw new AtomicMoveNotSupportedException(source.toString(),
                        destination.toString(), "simulated");
                }
                Files.move(source, destination, options);
                if (source.equals(temporary) && destination.equals(target)) {
                    throw new IOException("fallback failed after replacing target");
                }
            }

            @Override
            public void copy(Path source, Path destination, CopyOption... options)
                    throws IOException {
                Files.copy(source, destination, options);
            }

            @Override
            public boolean exists(Path path) {
                return Files.exists(path);
            }

            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                return Files.deleteIfExists(path);
            }
        };
    }

    private static String fullyPopulatedCatalogJson() {
        return "{\"formatVersion\":1,\"worldId\":\"World1\",\"displayName\":\"World\","
            + "\"versions\":[{\"id\":\"v1\",\"createdAt\":1,\"trigger\":\"MANUAL\","
            + "\"mode\":\"FULL\",\"archiveName\":\"v1.zip\",\"parentId\":null,"
            + "\"baseId\":null,\"visible\":true,\"originalBytes\":2,\"archiveBytes\":3,"
            + "\"durationMillis\":4,\"manifest\":{\"files\":{\"level.dat\":{"
            + "\"size\":12,\"modifiedTimeMillis\":34,\"sha256\":\"abc\"}}},"
            + "\"changedPaths\":[\"level.dat\"],\"deletedPaths\":[\"old.dat\"]}]}";
    }
}
