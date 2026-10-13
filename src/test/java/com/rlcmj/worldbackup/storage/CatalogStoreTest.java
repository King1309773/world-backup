package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupResult;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CatalogStoreTest {
    @Test
    public void oversizedSavePreservesPreviousCatalogAndCleansTemporaryFile()
            throws Exception {
        Path repo = temp.newFolder("oversized-save").toPath();
        BackupCatalog previous = BackupCatalog.empty("World1", "World");
        CatalogStore unrestricted = new CatalogStore();
        unrestricted.save(repo, previous);
        CatalogStore limited = new CatalogStore((target, writer, guard) ->
            AtomicFiles.write(target, writer, guard), 256);
        BackupCatalog oversized = BackupCatalog.empty("World1", repeat('x', 512));

        BackupException failure = assertThrows(BackupException.class,
            () -> limited.save(repo, oversized));

        assertEquals("worldbackup.error.catalog_too_large", failure.getTranslationKey());
        assertEquals(previous, unrestricted.load(repo));
        assertFalse(Files.exists(repo.resolve("index.json.tmp")));
    }

    @Test
    public void catalogExactlyAtConfiguredLimitRoundTrips() throws Exception {
        Path repo = temp.newFolder("catalog-at-limit").toPath();
        BackupCatalog catalog = BackupCatalog.empty("World1", repeat('x', 64));
        int encodedCharacters = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .create().toJson(catalog).length();
        CatalogStore store = new CatalogStore((target, writer, guard) ->
            AtomicFiles.write(target, writer, guard), encodedCharacters);

        store.save(repo, catalog);

        assertEquals(catalog, store.load(repo));
    }

    @Test
    public void oversizedLoadUsesExplicitSizeError() throws Exception {
        Path repo = temp.newFolder("oversized-load").toPath();
        Files.write(repo.resolve("index.json"), repeat(' ', 257)
            .getBytes(StandardCharsets.UTF_8));
        CatalogStore limited = new CatalogStore((target, writer, guard) ->
            AtomicFiles.write(target, writer, guard), 256);

        BackupException failure = assertThrows(BackupException.class,
            () -> limited.load(repo));

        assertEquals("worldbackup.error.catalog_too_large", failure.getTranslationKey());
    }

    @Test
    public void cancelledCatalogLoadStopsBeforeParsing() throws Exception {
        Path repo = temp.newFolder("cancel-load").toPath();
        Files.write(repo.resolve("index.json"), "{}".getBytes(StandardCharsets.UTF_8));
        BackupCancellation cancellation = new BackupCancellation();
        cancellation.cancel();

        BackupException error = org.junit.Assert.assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo, cancellation));

        assertEquals("worldbackup.error.cancelled", error.getTranslationKey());
    }

    @Test
    public void cancellationDoesNotWaitForBlockedPublishAndRollsItBack() throws Exception {
        BackupCancellation cancellation = new BackupCancellation();
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                cancellation.publish(() -> {
                    published.set(true);
                    publishing.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("interrupted");
                    }
                }, () -> published.set(false));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "blocked-catalog-publish");
        thread.start();
        assertTrue(publishing.await(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        cancellation.cancel();
        long elapsed = System.nanoTime() - started;

        assertTrue("cancel blocked on publish I/O", elapsed < TimeUnit.SECONDS.toNanos(1));
        release.countDown();
        thread.join(2000L);
        assertFalse(thread.isAlive());
        assertTrue(failure.get() instanceof InterruptedIOException);
        assertFalse(published.get());
    }

    @Test
    public void cancellationDuringBlockedCatalogMoveRestoresPreviousIndex() throws Exception {
        Path repo = temp.newFolder("blocked-catalog-move").toPath();
        Path index = repo.resolve("index.json");
        Files.write(index, "old index".getBytes(StandardCharsets.UTF_8));
        BackupCancellation cancellation = new BackupCancellation();
        BlockingFileOperations operations = new BlockingFileOperations();
        CatalogStore store = new CatalogStore((target, writer, guard) ->
            AtomicFiles.write(target, writer, guard, operations));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                store.save(repo, BackupCatalog.empty("world", "World"), cancellation);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "blocked-catalog-move");
        writer.start();
        assertTrue(operations.moved.await(2, TimeUnit.SECONDS));

        cancellation.cancel();
        operations.release.countDown();
        writer.join(2000L);

        assertFalse(writer.isAlive());
        assertTrue(failure.get() instanceof BackupException);
        assertEquals("worldbackup.error.cancelled",
            ((BackupException) failure.get()).getTranslationKey());
        assertEquals("old index",
            new String(Files.readAllBytes(index), StandardCharsets.UTF_8));
    }

    @Test
    public void failedCancellationRollbackPreservesCatalogRecoveryBackup() throws Exception {
        Path repo = temp.newFolder("blocked-catalog-rollback-failure").toPath();
        Path index = repo.resolve("index.json");
        Path backup = repo.resolve("index.json.bak");
        Files.write(index, "old index".getBytes(StandardCharsets.UTF_8));
        BackupCancellation cancellation = new BackupCancellation();
        BlockingFileOperations operations = new BlockingFileOperations(true);
        CatalogStore store = new CatalogStore((target, writer, guard) ->
            AtomicFiles.write(target, writer, guard, operations));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                store.save(repo, BackupCatalog.empty("world", "World"), cancellation);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "failed-catalog-rollback");
        writer.start();
        assertTrue(operations.moved.await(2, TimeUnit.SECONDS));
        cancellation.cancel();
        operations.release.countDown();
        writer.join(2000L);

        assertFalse(writer.isAlive());
        assertTrue(failure.get() instanceof BackupException);
        assertTrue(Files.exists(backup));
        assertEquals("old index",
            new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
    }

    @Test
    public void cancelledBackupCannotCrossCatalogPublishGate() throws Exception {
        Path repo = temp.newFolder("cancelled-publish").toPath();
        BackupCancellation cancellation = new BackupCancellation();
        cancellation.cancel();
        AtomicBoolean published = new AtomicBoolean();
        CatalogStore store = new CatalogStore((target, writer, publishGuard) -> {
            publishGuard.publish(() -> published.set(true));
        });

        BackupException failure = assertThrows(BackupException.class,
            () -> store.save(repo, BackupCatalog.empty("world", "World"), cancellation));

        assertEquals("worldbackup.error.cancelled", failure.getTranslationKey());
        assertFalse(published.get());
    }

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
    public void catalogWriteFailureIsCheckedAndPreservesExistingIndex() throws Exception {
        Path repo = temp.newFolder("catalog-write-failure").toPath();
        Path index = repo.resolve("index.json");
        Path temporary = repo.resolve("index.json.tmp");
        Files.write(index, "old index".getBytes(StandardCharsets.UTF_8));
        IOException diskFailure = new IOException("simulated mid-write failure");
        CatalogStore store = new CatalogStore((target, writer, publishGuard) ->
            AtomicFiles.write(target,
                output -> writer.accept(failingAfterFourBytes(output, diskFailure)),
                publishGuard));
        char[] displayName = new char[20000];
        Arrays.fill(displayName, 'W');

        BackupException error = assertThrows(BackupException.class,
            () -> store.save(repo, BackupCatalog.empty("World1", new String(displayName))));

        assertEquals("worldbackup.error.catalog_write", error.getTranslationKey());
        assertTrue(containsCause(error, diskFailure));
        assertEquals("old index",
            new String(Files.readAllBytes(index), StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporary));
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
    public void rejectsLenientJsonDuplicateFieldsAndTrailingContent() throws Exception {
        Path repo = temp.newFolder("strict-json").toPath();
        String valid = fullyPopulatedCatalogJson();
        for (String json : Arrays.asList(
            "{/*comment*/\"formatVersion\":1,\"worldId\":\"World1\","
                + "\"displayName\":\"World\",\"versions\":[]}",
            "{'formatVersion':1,'worldId':'World1','displayName':'World','versions':[]}",
            "{formatVersion:1,worldId:\"World1\",displayName:\"World\",versions:[]}",
            "{\"formatVersion\":1,\"formatVersion\":1,\"worldId\":\"World1\","
                + "\"displayName\":\"World\",\"versions\":[]}",
            valid.replace("\"id\":\"v1\"", "\"id\":\"v1\",\"id\":\"v2\""),
            valid + " true",
            "{\"formatVersion\":1,\"worldId\":\"World1\","
                + "\"displayName\":\"World\",\"versions\":[],}",
            "{\"formatVersion\":NaN,\"worldId\":\"World1\","
                + "\"displayName\":\"World\",\"versions\":[]}",
            "{\"formatVersion\":Infinity,\"worldId\":\"World1\","
                + "\"displayName\":\"World\",\"versions\":[]}"
        )) {
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupException error = assertThrows("Expected strict JSON rejection for " + json,
                BackupException.class, () -> new CatalogStore().load(repo));

            assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        }
    }

    @Test
    public void rejectsRawControlCharactersAndInvalidStringEscapes() throws Exception {
        Path repo = temp.newFolder("strict-json-strings").toPath();
        String valid = fullyPopulatedCatalogJson();
        for (String json : Arrays.asList(
            valid.replace("\"displayName\":\"World\"", "\"displayName\":\"Wo\nrld\""),
            valid.replace("\"displayName\":\"World\"", "\"displayName\":\"Wo\trld\""),
            valid.replace("\"displayName\":\"World\"", "\"displayName\":\"Wo\u0000rld\""),
            valid.replace("\"displayName\":\"World\"", "\"displayName\":\"Wo\\\nrld\""),
            valid.replace("\"displayName\":\"World\"", "\"displayName\":\"Wo\\xrld\"")
        )) {
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupException error = assertThrows("Expected strict string rejection",
                BackupException.class, () -> new CatalogStore().load(repo));

            assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        }
    }

    @Test
    public void acceptsValidEscapedControlCharactersInStrings() throws Exception {
        Path repo = temp.newFolder("escaped-json-strings").toPath();
        String valid = fullyPopulatedCatalogJson();
        for (String escaped : Arrays.asList("World\\nName", "World\\u000AName")) {
            String json = valid.replace("\"displayName\":\"World\"",
                "\"displayName\":\"" + escaped + "\"");
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupCatalog loaded = new CatalogStore().load(repo);

            assertEquals("World\nName", loaded.getDisplayName());
        }
    }

    @Test
    public void ignoresUnknownFieldsInCurrentCatalogVersion() throws Exception {
        Path repo = temp.newFolder("unknown-fields").toPath();
        String json = fullyPopulatedCatalogJson()
            .replace("\"worldId\":\"World1\"", "\"unknown\":{\"nested\":true},"
                + "\"worldId\":\"World1\"")
            .replace("\"id\":\"v1\"", "\"futureValue\":17,\"id\":\"v1\"");
        Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

        BackupCatalog loaded = new CatalogStore().load(repo);

        assertEquals("World1", loaded.getWorldId());
        assertEquals(1, loaded.getVersions().size());
    }

    @Test
    public void rejectsExcessivelyDeepUnknownJsonWithoutStackOverflow() throws Exception {
        Path repo = temp.newFolder("deep-json-rejected").toPath();
        Files.write(repo.resolve("index.json"),
            catalogWithDeepUnknownValue(CatalogStore.MAX_JSON_DEPTH)
            .getBytes(StandardCharsets.UTF_8));

        BackupException error = assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo));

        assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        assertFalse(error.getCause() instanceof StackOverflowError);
    }

    @Test
    public void acceptsUnknownJsonWithinDepthLimit() throws Exception {
        Path repo = temp.newFolder("deep-json-valid").toPath();
        Files.write(repo.resolve("index.json"),
            catalogWithDeepUnknownValue(CatalogStore.MAX_JSON_DEPTH - 2)
            .getBytes(StandardCharsets.UTF_8));

        BackupCatalog loaded = new CatalogStore().load(repo);

        assertEquals("World1", loaded.getWorldId());
        assertTrue(loaded.getVersions().isEmpty());
    }

    @Test
    public void missingCatalogIsReportedAsReadFailure() throws Exception {
        Path repo = temp.newFolder("missing-catalog").toPath();

        BackupException error = assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo));

        assertEquals("worldbackup.error.catalog_read", error.getTranslationKey());
        assertTrue(error.getCause() instanceof IOException);
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
    public void catalogSaveDoesNotFollowPreexistingTempSymlink() throws Exception {
        Path directory = temp.newFolder("catalog-temp-symlink").toPath();
        Path repo = Files.createDirectories(directory.resolve("repo"));
        Path canary = directory.resolve("outside-canary");
        Path temporary = repo.resolve("index.json.tmp");
        Files.write(canary, "untouched".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(temporary, canary);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        BackupException failure = assertThrows(BackupException.class,
            () -> new CatalogStore().save(repo, BackupCatalog.empty("World1", "World")));

        assertEquals("worldbackup.error.catalog_write", failure.getTranslationKey());
        assertEquals("untouched", new String(Files.readAllBytes(canary),
            StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(repo.resolve("index.json"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void catalogSaveDoesNotTruncatePreexistingTempHardlink() throws Exception {
        Path directory = temp.newFolder("catalog-temp-hardlink").toPath();
        Path repo = Files.createDirectories(directory.resolve("repo"));
        Path canary = directory.resolve("outside-canary");
        Path temporary = repo.resolve("index.json.tmp");
        Files.write(canary, "untouched".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createLink(temporary, canary);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Hard links are unavailable", unavailable);
        }

        BackupException failure = assertThrows(BackupException.class,
            () -> new CatalogStore().save(repo, BackupCatalog.empty("World1", "World")));

        assertEquals("worldbackup.error.catalog_write", failure.getTranslationKey());
        assertEquals("untouched", new String(Files.readAllBytes(canary),
            StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(repo.resolve("index.json"), LinkOption.NOFOLLOW_LINKS));
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
    public void successfulNonAtomicFallbackReplacesTargetAndCleansArtifacts() throws Exception {
        Path directory = temp.newFolder("fallback-success").toPath();
        Path target = directory.resolve("index.json");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));

        AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)),
            atomicMoveUnsupported());

        assertEquals("new", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(directory.resolve("index.json.tmp")));
        assertFalse(Files.exists(directory.resolve("index.json.bak")));
    }

    @Test
    public void failedNonAtomicFallbackLeavesNewTargetAbsent() throws Exception {
        Path directory = temp.newFolder("fallback-new-file").toPath();
        Path target = directory.resolve("index.json");
        Path temporary = directory.resolve("index.json.tmp");
        Path backup = directory.resolve("index.json.bak");

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
                if (path.equals(backup) && ++backupDeletes[0] == 1) {
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
    public void replacementAndRollbackFailurePreserveRecoveryBackup() throws Exception {
        assertRollbackFailurePreservesBackup("replacement-rollback-failure", true);
    }

    @Test
    public void cleanupAndRollbackFailurePreserveRecoveryBackup() throws Exception {
        assertRollbackFailurePreservesBackup("cleanup-rollback-failure", false);
    }

    @Test
    public void existingRecoveryBackupBlocksSubsequentFallbackAttempt() throws Exception {
        Path directory = temp.newFolder("existing-recovery-backup").toPath();
        Path target = directory.resolve("index.json");
        Path temporary = directory.resolve("index.json.tmp");
        Path backup = directory.resolve("index.json.bak");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
        IOException replacementFailure = new IOException("replacement failed after move");
        IOException rollbackFailure = new IOException("rollback copy failed after mutation");

        assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)),
            rollbackCopyFailureOperations(temporary, target, backup, true,
                replacementFailure, rollbackFailure)));
        byte[] targetBeforeRetry = Files.readAllBytes(target);
        byte[] backupBeforeRetry = Files.readAllBytes(backup);

        IOException retryFailure = assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("retry".getBytes(StandardCharsets.UTF_8)),
            atomicMoveUnsupported()));

        assertTrue(retryFailure.getMessage().contains(
            backup.toAbsolutePath().normalize().toString()));
        assertTrue(Arrays.equals(targetBeforeRetry, Files.readAllBytes(target)));
        assertTrue(Arrays.equals(backupBeforeRetry, Files.readAllBytes(backup)));
        assertFalse(Files.exists(temporary));
    }

    @Test
    public void existingRecoveryBackupSymlinkIsNotFollowedOrOverwritten() throws Exception {
        Path directory = temp.newFolder("existing-recovery-backup-symlink").toPath();
        Path target = directory.resolve("index.json");
        Path backup = directory.resolve("index.json.bak");
        Path canary = directory.resolve("outside-canary");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
        Files.write(canary, "untouched".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(backup, canary);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)),
            atomicMoveUnsupported()));

        assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertEquals("untouched", new String(Files.readAllBytes(canary),
            StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(backup));
        assertFalse(Files.exists(directory.resolve("index.json.tmp"),
            LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void existingRecoveryBackupHardlinkIsNotTruncatedOrOverwritten() throws Exception {
        Path directory = temp.newFolder("existing-recovery-backup-hardlink").toPath();
        Path target = directory.resolve("index.json");
        Path backup = directory.resolve("index.json.bak");
        Path canary = directory.resolve("outside-canary");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
        Files.write(canary, "untouched".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createLink(backup, canary);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Hard links are unavailable", unavailable);
        }

        assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)),
            atomicMoveUnsupported()));

        assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertEquals("untouched", new String(Files.readAllBytes(canary),
            StandardCharsets.UTF_8));
        assertTrue(Files.isSameFile(backup, canary));
        assertFalse(Files.exists(directory.resolve("index.json.tmp"),
            LinkOption.NOFOLLOW_LINKS));
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
        manifest.put("data\\level.dat", second);

        assertEquals(Arrays.asList("z.dat", "data/level.dat"),
            new ArrayList<>(manifest.getFiles().keySet()));
        assertEquals(second, manifest.get("data/level.dat"));
    }

    @Test
    public void manifestRejectsUnsafePaths() {
        SnapshotManifest manifest = new SnapshotManifest();
        FileFingerprint fingerprint = new FileFingerprint(1L, 2L, "hash");

        for (String path : Arrays.asList("../level.dat", "data/../level.dat", "/level.dat",
            "C:level.dat", "C:/level.dat", "C:\\level.dat", "\\\\server\\level.dat",
            "data//level.dat", "data/./level.dat", "data/\u0000.dat", "foo:bar",
            "region/NUL", "level.dat.", "level.dat ", "region/CON.txt", "COM1.log")) {
            assertThrows("Expected unsafe path rejection for " + path,
                IllegalArgumentException.class, () -> manifest.put(path, fingerprint));
        }
        assertTrue(manifest.getFiles().isEmpty());
    }

    @Test
    public void versionRejectsUnsafeArchiveBasenames() {
        for (String archiveName : Arrays.asList("../../outside.zip", "dir/archive.zip",
            "dir\\archive.zip", "bad\u0000.zip", "foo:bar.zip", "NUL.zip", "archive.zip.",
            "archive.zip ")) {
            assertThrows("Expected unsafe archive rejection for " + archiveName,
                IllegalArgumentException.class, () -> BackupVersion.full("v1", 1L,
                    BackupTrigger.MANUAL, archiveName, 0L, 0L, 0L,
                    new SnapshotManifest()));
        }
    }

    @Test
    public void rejectsUnsafeArchiveNameFromCatalog() throws Exception {
        Path repo = temp.newFolder("unsafe-archive-catalog").toPath();
        String json = fullyPopulatedCatalogJson().replace("v1.zip", "../../outside.zip");
        Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

        BackupException error = assertThrows(BackupException.class,
            () -> new CatalogStore().load(repo));

        assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
    }

    @Test
    public void rejectsUnsafeManifestPathsFromCatalog() throws Exception {
        Path repo = temp.newFolder("unsafe-manifest-catalog").toPath();
        String valid = fullyPopulatedCatalogJson();
        for (String json : Arrays.asList(
            valid.replace("\"level.dat\"", "\"region/NUL\""),
            valid.replace("\"level.dat\"", "\"region/foo:bar\""),
            valid.replace("\"level.dat\"", "\"trailing.\""),
            valid.replace("\"level.dat\"", "\"bad\\u0000name\"")
        )) {
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupException error = assertThrows(BackupException.class,
                () -> new CatalogStore().load(repo));

            assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        }
    }

    @Test
    public void constructorsRejectBlankTextAndNegativeSizesOrDurations() {
        SnapshotManifest manifest = new SnapshotManifest();

        assertThrows(IllegalArgumentException.class,
            () -> BackupCatalog.empty(" ", "World"));
        assertThrows(IllegalArgumentException.class,
            () -> BackupCatalog.empty("World1", "\t"));
        assertThrows(IllegalArgumentException.class,
            () -> new FileFingerprint(-1L, -10L, "hash"));
        assertThrows(IllegalArgumentException.class,
            () -> new FileFingerprint(0L, -10L, " "));
        assertEquals(-10L, new FileFingerprint(0L, -10L, "hash").getModifiedTimeMillis());

        assertThrows(IllegalArgumentException.class, () -> BackupVersion.full(" ", 1L,
            BackupTrigger.MANUAL, "v1.zip", 0L, 0L, 0L, manifest));
        assertThrows(IllegalArgumentException.class, () -> BackupVersion.full("v1", -1L,
            BackupTrigger.MANUAL, "v1.zip", 0L, 0L, 0L, manifest));
        assertThrows(IllegalArgumentException.class, () -> BackupVersion.full("v1", 1L,
            BackupTrigger.MANUAL, "v1.zip", -1L, 0L, 0L, manifest));
        assertThrows(IllegalArgumentException.class, () -> BackupVersion.full("v1", 1L,
            BackupTrigger.MANUAL, "v1.zip", 0L, -1L, 0L, manifest));
        assertThrows(IllegalArgumentException.class, () -> BackupVersion.full("v1", 1L,
            BackupTrigger.MANUAL, "v1.zip", 0L, 0L, -1L, manifest));
        assertThrows(IllegalArgumentException.class, () -> new BackupVersion("v1", 1L,
            BackupTrigger.MANUAL, com.rlcmj.worldbackup.core.BackupMode.INCREMENTAL,
            "v1.zip", " ", "base", true, 0L, 0L, 0L, manifest,
            Arrays.asList("level.dat"), Arrays.asList("old.dat")));
    }

    @Test
    public void rejectsInvalidLoadedCatalogValueSemantics() throws Exception {
        Path repo = temp.newFolder("invalid-catalog-values").toPath();
        String valid = fullyPopulatedCatalogJson();
        for (String json : Arrays.asList(
            valid.replace("\"worldId\":\"World1\"", "\"worldId\":\" \""),
            valid.replace("\"displayName\":\"World\"", "\"displayName\":\" \""),
            valid.replace("\"id\":\"v1\"", "\"id\":\" \""),
            valid.replace("\"archiveName\":\"v1.zip\"", "\"archiveName\":\" \""),
            valid.replace("\"parentId\":null", "\"parentId\":\" \""),
            valid.replace("\"baseId\":null", "\"baseId\":\" \""),
            valid.replace("\"createdAt\":1", "\"createdAt\":-1"),
            valid.replace("\"originalBytes\":2", "\"originalBytes\":-2"),
            valid.replace("\"archiveBytes\":3", "\"archiveBytes\":-3"),
            valid.replace("\"durationMillis\":4", "\"durationMillis\":-4"),
            valid.replace("\"size\":12", "\"size\":-12"),
            valid.replace("\"sha256\":\"abc\"", "\"sha256\":\" \"")
        )) {
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupException error = assertThrows(BackupException.class,
                () -> new CatalogStore().load(repo));

            assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        }
    }

    @Test
    public void rejectsNonIntegralOrOutOfRangeLongNumbers() throws Exception {
        Path repo = temp.newFolder("invalid-long-numbers").toPath();
        String valid = fullyPopulatedCatalogJson();
        for (String json : Arrays.asList(
            valid.replace("\"createdAt\":1", "\"createdAt\":1e0"),
            valid.replace("\"size\":12", "\"size\":12.0"),
            valid.replace("\"originalBytes\":2",
                "\"originalBytes\":9223372036854775808")
        )) {
            Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

            BackupException error = assertThrows(BackupException.class,
                () -> new CatalogStore().load(repo));

            assertEquals("worldbackup.error.catalog_corrupt", error.getTranslationKey());
        }
    }

    @Test
    public void acceptsPreEpochModifiedTimeFromCatalog() throws Exception {
        Path repo = temp.newFolder("pre-epoch-modified-time").toPath();
        String json = fullyPopulatedCatalogJson()
            .replace("\"modifiedTimeMillis\":34", "\"modifiedTimeMillis\":-34");
        Files.write(repo.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));

        BackupCatalog loaded = new CatalogStore().load(repo);

        assertEquals(-34L, loaded.getVersions().get(0).getManifest()
            .get("level.dat").getModifiedTimeMillis());
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
    public void backupResultsRejectBlankKeysAndNegativeMeasurements() {
        assertThrows(IllegalArgumentException.class,
            () -> BackupResult.success(" ", 0L, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> BackupResult.failure("\t", 0L, 0L, 0L));

        for (long[] values : Arrays.asList(
            new long[] {-1L, 0L, 0L},
            new long[] {0L, -1L, 0L},
            new long[] {0L, 0L, -1L}
        )) {
            assertThrows(IllegalArgumentException.class,
                () -> BackupResult.success("v1", values[0], values[1], values[2]));
            assertThrows(IllegalArgumentException.class,
                () -> BackupResult.failure("worldbackup.error.failed",
                    values[0], values[1], values[2]));
        }
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

    @Test
    public void backupExceptionRejectsBlankTranslationKey() {
        assertThrows(IllegalArgumentException.class, () -> new BackupException(" "));
        assertThrows(IllegalArgumentException.class,
            () -> new BackupException("\t", new IOException("cause")));
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

    private static final class BlockingFileOperations implements AtomicFiles.FileOperations {
        private final CountDownLatch moved = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean failRollbackCopy;

        private BlockingFileOperations() { this(false); }
        private BlockingFileOperations(boolean failRollbackCopy) {
            this.failRollbackCopy = failRollbackCopy;
        }

        @Override public void move(Path source, Path target, CopyOption... options)
                throws IOException {
            Files.move(source, target, options);
            moved.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
        }

        @Override public void copy(Path source, Path target, CopyOption... options)
                throws IOException {
            if (failRollbackCopy && source.getFileName().toString().endsWith(".bak")) {
                throw new IOException("rollback copy failed");
            }
            Files.copy(source, target, options);
        }

        @Override public boolean exists(Path path) { return Files.exists(path); }
        @Override public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }
    }

    private static AtomicFiles.FileOperations atomicMoveUnsupported() {
        return new AtomicFiles.FileOperations() {
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
                return Files.deleteIfExists(path);
            }
        };
    }

    private void assertRollbackFailurePreservesBackup(String directoryName,
                                                       boolean failReplacement) throws Exception {
        Path directory = temp.newFolder(directoryName).toPath();
        Path target = directory.resolve("index.json");
        Path temporary = directory.resolve("index.json.tmp");
        Path backup = directory.resolve("index.json.bak");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
        IOException primaryFailure = new IOException(failReplacement
            ? "replacement failed after move" : "backup cleanup failed");
        IOException rollbackFailure = new IOException("rollback failed");
        AtomicFiles.FileOperations failures = rollbackCopyFailureOperations(temporary, target,
            backup, failReplacement, primaryFailure, rollbackFailure);

        IOException failure = assertThrows(IOException.class, () -> AtomicFiles.write(target,
            output -> output.write("new".getBytes(StandardCharsets.UTF_8)), failures));

        assertTrue(failure.getMessage().contains(primaryFailure.getMessage()));
        assertTrue(failure.getMessage().contains(backup.toAbsolutePath().normalize().toString()));
        assertSame(primaryFailure, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(rollbackFailure, failure.getSuppressed()[0]);
        assertEquals("old", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
        assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporary));
    }

    private static AtomicFiles.FileOperations rollbackCopyFailureOperations(Path temporary,
            Path target, Path backup, boolean failReplacement, IOException primaryFailure,
            IOException rollbackFailure) {
        int[] backupDeletes = {0};
        return new AtomicFiles.FileOperations() {
            @Override
            public void move(Path source, Path destination, CopyOption... options)
                    throws IOException {
                if (Arrays.asList(options).contains(java.nio.file.StandardCopyOption.ATOMIC_MOVE)) {
                    throw new AtomicMoveNotSupportedException(source.toString(),
                        destination.toString(), "simulated");
                }
                Files.move(source, destination, options);
                if (failReplacement && source.equals(temporary) && destination.equals(target)) {
                    throw primaryFailure;
                }
            }

            @Override
            public void copy(Path source, Path destination, CopyOption... options)
                    throws IOException {
                Files.copy(source, destination, options);
                if (source.equals(backup) && destination.equals(target)) {
                    throw rollbackFailure;
                }
            }

            @Override
            public boolean exists(Path path) {
                return Files.exists(path);
            }

            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                if (!failReplacement && path.equals(backup) && ++backupDeletes[0] == 1) {
                    throw primaryFailure;
                }
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

    private static OutputStream failingAfterFourBytes(OutputStream output, IOException failure) {
        return new OutputStream() {
            private int count;

            @Override
            public void write(int value) throws IOException {
                if (count++ >= 4) {
                    throw failure;
                }
                output.write(value);
            }
        };
    }

    private static boolean containsCause(Throwable failure, Throwable expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current == expected) {
                return true;
            }
        }
        return false;
    }

    private static String catalogWithDeepUnknownValue(int depth) {
        String value = "0";
        for (int index = 0; index < depth; index++) {
            value = "{\"nested\":" + value + "}";
        }
        return "{\"formatVersion\":1,\"worldId\":\"World1\","
            + "\"displayName\":\"World\",\"versions\":[],\"unknown\":" + value + "}";
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }
}
