package com.rlcmj.worldbackup.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ArchiveStoreSecurityTest {
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesFullArchiveWithSortedDataAndCompleteManifest() throws Exception {
        Path world = temp.newFolder("full-world").toPath();
        write(world.resolve("z.dat"), "last");
        write(world.resolve("region/a.mca"), "first");
        SnapshotManifest snapshot = manifest(world, "z.dat", "region/a.mca");
        Path repo = temp.newFolder("full-repo").toPath();

        Path archive = new ArchiveStore().write(repo, "full.zip", world, snapshot,
            Arrays.asList("z.dat", "region/a.mca"), Collections.emptyList(),
            new BackupCancellation());

        assertEquals(repo.resolve("full.zip"), archive);
        assertEquals(Arrays.asList("data/region/a.mca", "data/z.dat", "manifest.json"),
            entryNames(archive));
        assertEquals("first", entryText(archive, "data/region/a.mca"));
        assertEquals("last", entryText(archive, "data/z.dat"));
        assertEquals(snapshot, new ArchiveStore().readManifest(archive));

        JsonObject metadata = new JsonParser().parse(entryText(archive, "manifest.json"))
            .getAsJsonObject();
        assertEquals(snapshot.size(), metadata.getAsJsonObject("snapshot")
            .getAsJsonObject("files").size());
        assertEquals(Arrays.asList("region/a.mca", "z.dat"),
            strings(metadata.getAsJsonArray("includedPaths")));
        assertEquals(0, metadata.getAsJsonArray("deletedPaths").size());
    }

    @Test
    public void writesDeltaPayloadOnlyButEmbedsCompleteSnapshotAndDeletions() throws Exception {
        Path world = temp.newFolder("delta-world").toPath();
        write(world.resolve("same.dat"), "same");
        write(world.resolve("changed.dat"), "new");
        SnapshotManifest snapshot = manifest(world, "same.dat", "changed.dat");
        Path archive = new ArchiveStore().write(temp.newFolder("delta-repo").toPath(),
            "delta.zip", world, snapshot, Collections.singletonList("changed.dat"),
            Collections.singletonList("gone.dat"), new BackupCancellation());

        assertEquals(Arrays.asList("data/changed.dat", "manifest.json"), entryNames(archive));
        JsonObject metadata = new JsonParser().parse(entryText(archive, "manifest.json"))
            .getAsJsonObject();
        assertEquals(2, metadata.getAsJsonObject("snapshot").getAsJsonObject("files").size());
        assertEquals(Collections.singletonList("changed.dat"),
            strings(metadata.getAsJsonArray("includedPaths")));
        assertEquals(Collections.singletonList("gone.dat"),
            strings(metadata.getAsJsonArray("deletedPaths")));
    }

    @Test
    public void everyWrittenEntryCanBeReadToEofWithMatchingCrc() throws Exception {
        Path world = temp.newFolder("crc-world").toPath();
        write(world.resolve("large.dat"), repeat('x', 20000));
        SnapshotManifest snapshot = manifest(world, "large.dat");
        Path archive = new ArchiveStore().write(temp.newFolder("crc-repo").toPath(),
            "crc.zip", world, snapshot, Collections.singletonList("large.dat"),
            Collections.emptyList(), new BackupCancellation());

        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                CRC32 crc = new CRC32();
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        crc.update(buffer, 0, count);
                    }
                }
                assertEquals(entry.getCrc(), crc.getValue());
            }
        }
    }

    @Test
    public void cancelledWriteLeavesNeitherFinalNorTemporaryArchive() throws Exception {
        Path world = temp.newFolder("cancel-world").toPath();
        write(world.resolve("level.dat"), "level");
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = temp.newFolder("cancel-repo").toPath();
        BackupCancellation cancellation = new BackupCancellation();
        cancellation.cancel();

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().write(repo, "cancelled.zip", world, snapshot,
                Collections.singletonList("level.dat"), Collections.emptyList(),
                cancellation));

        assertEquals("worldbackup.error.cancelled", error.getTranslationKey());
        assertFalse(Files.exists(repo.resolve("cancelled.zip")));
        assertFalse(Files.exists(repo.resolve("cancelled.zip.tmp")));
        try (java.util.stream.Stream<Path> files = Files.list(repo)) {
            assertEquals(0L, files.count());
        }
    }

    @Test
    public void failedWritePreservesExistingArchiveAndRemovesTemporaryFiles() throws Exception {
        Path world = temp.newFolder("failed-world").toPath();
        Path source = write(world.resolve("level.dat"), "before");
        SnapshotManifest snapshot = manifest(world, "level.dat");
        write(source, "after");
        Path repo = temp.newFolder("failed-repo").toPath();
        Path existing = write(repo.resolve("backup.zip"), "old archive");

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().write(repo, "backup.zip", world, snapshot,
                Collections.singletonList("level.dat"), Collections.emptyList(),
                new BackupCancellation()));

        assertEquals("worldbackup.error.archive_write", error.getTranslationKey());
        assertEquals("old archive", new String(Files.readAllBytes(existing),
            StandardCharsets.UTF_8));
        assertFalse(Files.exists(repo.resolve("backup.zip.tmp")));
        try (java.util.stream.Stream<Path> files = Files.list(repo)) {
            assertEquals(Collections.singletonList("backup.zip"), files
                .map(path -> path.getFileName().toString())
                .sorted()
                .collect(java.util.stream.Collectors.toList()));
        }
    }

    @Test
    public void rejectsUnsafeArchiveNamesWithoutWritingOutsideRepository() throws Exception {
        Path directory = temp.newFolder("archive-name").toPath();
        Path world = Files.createDirectories(directory.resolve("world"));
        write(world.resolve("level.dat"), "level");
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = Files.createDirectories(directory.resolve("repo"));

        for (String unsafe : Arrays.asList("../escape.zip", "..\\escape.zip",
                "/absolute.zip", "C:/drive.zip", "C:\\drive.zip",
                "\\\\server\\share.zip", "nested/archive.zip")) {
            BackupException error = assertThrows(BackupException.class,
                () -> new ArchiveStore().write(repo, unsafe, world, snapshot,
                    Collections.singletonList("level.dat"), Collections.emptyList(),
                    new BackupCancellation()));
            assertEquals("worldbackup.error.archive_write", error.getTranslationKey());
        }

        try (java.util.stream.Stream<Path> files = Files.list(repo)) {
            assertEquals(0L, files.count());
        }
        assertFalse(Files.exists(directory.resolve("escape.zip")));
    }

    @Test
    public void fallbackDoesNotFollowPreexistingFixedPublishTempSymlink() throws Exception {
        Path directory = temp.newFolder("fallback-symlink").toPath();
        Path world = Files.createDirectories(directory.resolve("world"));
        write(world.resolve("level.dat"), "level");
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = Files.createDirectories(directory.resolve("repo"));
        Path canary = write(directory.resolve("outside-canary"), "untouched");
        Path fixedTemp = repo.resolve("backup.zip.tmp");
        try {
            Files.createSymbolicLink(fixedTemp, canary);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        Path archive = new ArchiveStore(new ForcedFallbackOperations(null, false)).write(
            repo, "backup.zip", world, snapshot, Collections.singletonList("level.dat"),
            Collections.emptyList(), new BackupCancellation());

        assertEquals("untouched", new String(Files.readAllBytes(canary),
            StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(fixedTemp));
        assertEquals(snapshot, new ArchiveStore().readManifest(archive));
    }

    @Test
    public void fallbackDoesNotTruncatePreexistingFixedPublishTempHardlink() throws Exception {
        Path directory = temp.newFolder("fallback-hardlink").toPath();
        Path world = Files.createDirectories(directory.resolve("world"));
        write(world.resolve("level.dat"), "level");
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = Files.createDirectories(directory.resolve("repo"));
        Path canary = write(directory.resolve("outside-canary"), "untouched");
        Path fixedTemp = repo.resolve("backup.zip.tmp");
        try {
            Files.createLink(fixedTemp, canary);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Hard links are unavailable", unavailable);
        }

        Path archive = new ArchiveStore(new ForcedFallbackOperations(null, false)).write(
            repo, "backup.zip", world, snapshot, Collections.singletonList("level.dat"),
            Collections.emptyList(), new BackupCancellation());

        assertEquals("untouched", new String(Files.readAllBytes(canary),
            StandardCharsets.UTF_8));
        assertTrue(Files.isSameFile(fixedTemp, canary));
        assertEquals(snapshot, new ArchiveStore().readManifest(archive));
    }

    @Test
    public void cancellationDuringFallbackCopyPreservesOldFinalAndCleansUniqueTemps()
            throws Exception {
        Path world = temp.newFolder("fallback-cancel-world").toPath();
        write(world.resolve("level.dat"), repeat('x', 20000));
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = temp.newFolder("fallback-cancel-repo").toPath();
        Path existing = write(repo.resolve("backup.zip"), "old archive");
        BackupCancellation cancellation = new BackupCancellation();
        ArchiveStore store = new ArchiveStore(
            new ForcedFallbackOperations(cancellation, true));

        BackupException error = assertThrows(BackupException.class,
            () -> store.write(repo, "backup.zip", world, snapshot,
                Collections.singletonList("level.dat"), Collections.emptyList(),
                cancellation));

        assertEquals("worldbackup.error.cancelled", error.getTranslationKey());
        assertEquals("old archive", new String(Files.readAllBytes(existing),
            StandardCharsets.UTF_8));
        try (java.util.stream.Stream<Path> files = Files.list(repo)) {
            assertEquals(Collections.singletonList("backup.zip"), files
                .map(path -> path.getFileName().toString())
                .sorted()
                .collect(java.util.stream.Collectors.toList()));
        }
    }

    @Test
    public void cancellationDuringFallbackCopyLeavesNoNewFinalOrUniqueTemps()
            throws Exception {
        Path world = temp.newFolder("fallback-cancel-new-world").toPath();
        write(world.resolve("level.dat"), repeat('x', 20000));
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = temp.newFolder("fallback-cancel-new-repo").toPath();
        BackupCancellation cancellation = new BackupCancellation();
        ArchiveStore store = new ArchiveStore(
            new ForcedFallbackOperations(cancellation, true));

        BackupException error = assertThrows(BackupException.class,
            () -> store.write(repo, "backup.zip", world, snapshot,
                Collections.singletonList("level.dat"), Collections.emptyList(),
                cancellation));

        assertEquals("worldbackup.error.cancelled", error.getTranslationKey());
        try (java.util.stream.Stream<Path> files = Files.list(repo)) {
            assertEquals(0L, files.count());
        }
    }

    @Test
    public void failedFallbackReplacementPreservesOldFinalAndCleansUniqueTemps()
            throws Exception {
        Path world = temp.newFolder("fallback-failure-world").toPath();
        write(world.resolve("level.dat"), "level");
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = temp.newFolder("fallback-failure-repo").toPath();
        Path existing = write(repo.resolve("backup.zip"), "old archive");
        ArchiveStore store = new ArchiveStore(
            new ForcedFallbackOperations(null, false, true));

        BackupException error = assertThrows(BackupException.class,
            () -> store.write(repo, "backup.zip", world, snapshot,
                Collections.singletonList("level.dat"), Collections.emptyList(),
                new BackupCancellation()));

        assertEquals("worldbackup.error.archive_write", error.getTranslationKey());
        assertEquals("old archive", new String(Files.readAllBytes(existing),
            StandardCharsets.UTF_8));
        try (java.util.stream.Stream<Path> files = Files.list(repo)) {
            assertEquals(Collections.singletonList("backup.zip"), files
                .map(path -> path.getFileName().toString())
                .sorted()
                .collect(java.util.stream.Collectors.toList()));
        }
    }

    @Test
    public void rejectsUnsafeDuplicateDirectoryAndSymbolicLinkInputs() throws Exception {
        Path world = temp.newFolder("invalid-input-world").toPath();
        write(world.resolve("level.dat"), "level");
        Files.createDirectories(world.resolve("region"));
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = temp.newFolder("invalid-input-repo").toPath();
        ArchiveStore store = new ArchiveStore();

        assertThrows(BackupException.class, () -> store.write(repo, "escape.zip", world,
            snapshot, Collections.singletonList("../escape"), Collections.emptyList(),
            new BackupCancellation()));
        assertThrows(BackupException.class, () -> store.write(repo, "duplicate.zip", world,
            snapshot, Arrays.asList("level.dat", "level.dat"), Collections.emptyList(),
            new BackupCancellation()));
        SnapshotManifest caseCollision = new SnapshotManifest(snapshot);
        caseCollision.put("LEVEL.DAT", snapshot.get("level.dat"));
        assertThrows(BackupException.class, () -> store.write(repo, "case-collision.zip",
            world, caseCollision, Collections.emptyList(), Collections.emptyList(),
            new BackupCancellation()));
        assertThrows(BackupException.class, () -> store.write(repo,
            "deleted-case-collision.zip", world, snapshot, Collections.emptyList(),
            Arrays.asList("gone.dat", "GONE.DAT"), new BackupCancellation()));
        assertThrows(BackupException.class, () -> store.write(repo, "directory.zip", world,
            snapshot, Collections.singletonList("region"), Collections.emptyList(),
            new BackupCancellation()));

        Path link = world.resolve("linked.dat");
        try {
            Files.createSymbolicLink(link, world.resolve("level.dat"));
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }
        SnapshotManifest linkedSnapshot = new SnapshotManifest(snapshot);
        linkedSnapshot.put("linked.dat", snapshot.get("level.dat"));
        BackupException linkError = assertThrows(BackupException.class,
            () -> store.write(repo, "link.zip", world, linkedSnapshot,
                Collections.singletonList("linked.dat"), Collections.emptyList(),
                new BackupCancellation()));
        assertEquals("worldbackup.error.path_escape", linkError.getTranslationKey());
    }

    @Test
    public void rejectsPortableCollisionBetweenIncludedAndDeletedPaths() throws Exception {
        Path world = temp.newFolder("relationship-world").toPath();
        write(world.resolve("level.dat"), "level");
        SnapshotManifest snapshot = manifest(world, "level.dat");
        Path repo = temp.newFolder("relationship-repo").toPath();

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().write(repo, "collision.zip", world, snapshot,
                Collections.singletonList("level.dat"),
                Collections.singletonList("LEVEL.DAT"), new BackupCancellation()));

        assertEquals("worldbackup.error.archive_write", error.getTranslationKey());
        assertFalse(Files.exists(repo.resolve("collision.zip")));
    }

    @Test
    public void extractsOnlyDataEntries() throws Exception {
        Path archive = temp.newFile("extract.zip").toPath();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] region = bytes("region");
        entries.put("data/region/r.0.0.mca", region);
        entries.put("manifest.json", manifestMetadata("region/r.0.0.mca", region));
        entries.put("ignored/readme.txt", bytes("ignored"));
        createZip(archive, entries);
        Path target = temp.newFolder("extract-target").toPath();

        new ArchiveStore().extract(archive, target);

        assertArrayEquals(bytes("region"),
            Files.readAllBytes(target.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(target.resolve("manifest.json")));
        assertFalse(Files.exists(target.resolve("ignored/readme.txt")));
    }

    @Test
    public void rejectsZipSlipAbsoluteDriveAndNormalizedEscapes() throws Exception {
        for (String unsafe : Arrays.asList("../escape", "..\\escape", "/absolute",
                "\\absolute", "C:/drive", "C:\\drive", "\\\\server\\share",
                "data/nested/../../escape", "data\\nested\\..\\..\\escape")) {
            Path directory = temp.newFolder("unsafe-" + Math.abs(unsafe.hashCode())).toPath();
            Path archive = directory.resolve("unsafe.zip");
            createZip(archive, Collections.singletonMap(unsafe, bytes("attack")));
            Path target = Files.createDirectories(directory.resolve("target"));

            BackupException error = assertThrows(BackupException.class,
                () -> new ArchiveStore().extract(archive, target));

            assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
            assertFalse(Files.exists(directory.resolve("escape")));
            assertFalse(Files.exists(target.resolve("escape")));
        }
    }

    @Test
    public void rejectsDataEntryThroughExistingSymbolicLinkDirectory() throws Exception {
        Path directory = temp.newFolder("extract-link").toPath();
        Path outside = Files.createDirectories(directory.resolve("outside"));
        Path target = Files.createDirectories(directory.resolve("target"));
        Path link = target.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }
        Path archive = directory.resolve("link.zip");
        byte[] attack = bytes("attack");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("data/linked/escape.dat", attack);
        entries.put("manifest.json", manifestMetadata("linked/escape.dat", attack));
        createZip(archive, entries);

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().extract(archive, target));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertFalse(Files.exists(outside.resolve("escape.dat")));
    }

    @Test
    public void detectsCorruptManifestCrcDuringReadback() throws Exception {
        Path archive = temp.newFile("corrupt-crc.zip").toPath();
        byte[] manifest = bytes("{\"snapshot\":{\"files\":{}},"
            + "\"includedPaths\":[],\"deletedPaths\":[]}");
        createStoredZip(archive, "manifest.json", manifest);
        byte[] encoded = Files.readAllBytes(archive);
        int dataOffset = indexOf(encoded, manifest);
        assertTrue(dataOffset >= 0);
        encoded[dataOffset] ^= 1;
        Files.write(archive, encoded);

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().readManifest(archive));

        assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
    }

    @Test
    public void detectsCrcMismatchEvenWhenTamperedManifestRemainsValidJson() throws Exception {
        Path archive = temp.newFile("valid-json-bad-crc.zip").toPath();
        byte[] manifest = bytes("{ \"snapshot\": { \"files\": {} }, "
            + "\"includedPaths\": [], \"deletedPaths\": [] }");
        createStoredZip(archive, "manifest.json", manifest);
        byte[] encoded = Files.readAllBytes(archive);
        int dataOffset = indexOf(encoded, manifest);
        assertTrue(dataOffset >= 0);
        int space = indexOf(manifest, bytes(" "));
        assertTrue(space >= 0);
        encoded[dataOffset + space] = '\n';
        Files.write(archive, encoded);
        new JsonParser().parse(new String(Arrays.copyOfRange(encoded, dataOffset,
            dataOffset + manifest.length), StandardCharsets.UTF_8));

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().readManifest(archive));

        assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
    }

    @Test
    public void rejectsArchiveTruncatedAfterItsLastLocalEntry() throws Exception {
        Path archive = temp.newFile("truncated.zip").toPath();
        byte[] manifest = bytes("{\"snapshot\":{\"files\":{}},"
            + "\"includedPaths\":[],\"deletedPaths\":[]}");
        createZip(archive, Collections.singletonMap("manifest.json", manifest));
        byte[] encoded = Files.readAllBytes(archive);
        Files.write(archive, Arrays.copyOf(encoded, encoded.length - 8));

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().readManifest(archive));

        assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
    }

    @Test
    public void rejectsDuplicateEntryNames() throws Exception {
        Path archive = temp.newFile("duplicate.zip").toPath();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", bytes("{\"snapshot\":{\"files\":{}},"
            + "\"includedPaths\":[],\"deletedPaths\":[]}"));
        entries.put("junk/aaa.txt", bytes("first"));
        entries.put("junk/bbb.txt", bytes("second"));
        createZip(archive, entries);
        byte[] encoded = Files.readAllBytes(archive);
        replaceAll(encoded, bytes("junk/bbb.txt"), bytes("junk/aaa.txt"));
        Files.write(archive, encoded);

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().readManifest(archive));

        assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
    }

    @Test
    public void rejectsArchiveWhosePayloadDoesNotMatchIncludedPaths() throws Exception {
        Path archive = temp.newFile("mismatched-payload.zip").toPath();
        byte[] contents = bytes("expected");
        String metadata = "{\"snapshot\":{\"files\":{\"level.dat\":{"
            + "\"size\":" + contents.length + ",\"modifiedTimeMillis\":0,"
            + "\"sha256\":\"" + sha256(contents) + "\"}}},"
            + "\"includedPaths\":[\"level.dat\"],\"deletedPaths\":[]}";
        createZip(archive, Collections.singletonMap("manifest.json", bytes(metadata)));

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().readManifest(archive));

        assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
    }

    @Test
    public void rejectsArchiveWhosePayloadDoesNotMatchManifestFingerprint() throws Exception {
        Path archive = temp.newFile("mismatched-fingerprint.zip").toPath();
        byte[] expected = bytes("right");
        byte[] actual = bytes("wrong");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("data/level.dat", actual);
        entries.put("manifest.json", manifestMetadata("level.dat", expected));
        createZip(archive, entries);
        Path target = temp.newFolder("mismatched-target").toPath();

        BackupException readError = assertThrows(BackupException.class,
            () -> new ArchiveStore().readManifest(archive));
        BackupException extractError = assertThrows(BackupException.class,
            () -> new ArchiveStore().extract(archive, target));

        assertEquals("worldbackup.error.archive_corrupt", readError.getTranslationKey());
        assertEquals("worldbackup.error.archive_corrupt", extractError.getTranslationKey());
        assertFalse(Files.exists(target.resolve("level.dat")));
    }

    @Test
    public void rejectsMalformedSha256ForSnapshotOnlyPath() throws Exception {
        Path archive = temp.newFile("malformed-sha.zip").toPath();
        String metadata = "{\"snapshot\":{\"files\":{\"unchanged.dat\":{"
            + "\"size\":1,\"modifiedTimeMillis\":0,\"sha256\":\"not-a-sha256\"}}},"
            + "\"includedPaths\":[],\"deletedPaths\":[]}";
        createZip(archive, Collections.singletonMap("manifest.json", bytes(metadata)));

        BackupException error = assertThrows(BackupException.class,
            () -> new ArchiveStore().readManifest(archive));

        assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
    }

    private static SnapshotManifest manifest(Path world, String... paths) throws Exception {
        SnapshotManifest manifest = new SnapshotManifest();
        for (String path : paths) {
            Path file = world.resolve(path);
            byte[] contents = Files.readAllBytes(file);
            manifest.put(path, new FileFingerprint(contents.length,
                Files.getLastModifiedTime(file).toMillis(), sha256(contents)));
        }
        return manifest;
    }

    private static List<String> entryNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private static String entryText(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
             InputStream input = zip.getInputStream(zip.getEntry(name))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static Path write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes(value));
        return path;
    }

    private static void createZip(Path archive, Map<String, byte[]> entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static void createStoredZip(Path archive, String name, byte[] value)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(value);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(value.length);
        entry.setCompressedSize(value.length);
        entry.setCrc(crc.getValue());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive),
                StandardCharsets.UTF_8)) {
            zip.putNextEntry(entry);
            zip.write(value);
            zip.closeEntry();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] manifestMetadata(String path, byte[] contents) throws Exception {
        return bytes("{\"snapshot\":{\"files\":{\"" + path + "\":{"
            + "\"size\":" + contents.length + ",\"modifiedTimeMillis\":0,"
            + "\"sha256\":\"" + sha256(contents) + "\"}}},"
            + "\"includedPaths\":[\"" + path + "\"],\"deletedPaths\":[]}");
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static int indexOf(byte[] source, byte[] needle) {
        outer:
        for (int index = 0; index <= source.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (source[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static void replaceAll(byte[] source, byte[] needle, byte[] replacement) {
        if (needle.length != replacement.length) {
            throw new IllegalArgumentException("Replacement must preserve ZIP offsets");
        }
        int index = 0;
        int replacements = 0;
        while (index <= source.length - needle.length) {
            int found = indexOf(Arrays.copyOfRange(source, index, source.length), needle);
            if (found < 0) {
                break;
            }
            int absolute = index + found;
            System.arraycopy(replacement, 0, source, absolute, replacement.length);
            replacements++;
            index = absolute + replacement.length;
        }
        assertTrue("Expected duplicate name in local header and central directory",
            replacements >= 2);
    }

    private static final class ForcedFallbackOperations
            implements ArchiveStore.PublishOperations {
        private final BackupCancellation cancellation;
        private final boolean cancelDuringPublishCopy;
        private final boolean failReplacementMove;
        private boolean rejectedAtomicMove;

        private ForcedFallbackOperations(BackupCancellation cancellation,
                                         boolean cancelDuringPublishCopy) {
            this(cancellation, cancelDuringPublishCopy, false);
        }

        private ForcedFallbackOperations(BackupCancellation cancellation,
                                         boolean cancelDuringPublishCopy,
                                         boolean failReplacementMove) {
            this.cancellation = cancellation;
            this.cancelDuringPublishCopy = cancelDuringPublishCopy;
            this.failReplacementMove = failReplacementMove;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (!rejectedAtomicMove && Arrays.asList(options).contains(
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)) {
                rejectedAtomicMove = true;
                throw new AtomicMoveNotSupportedException(source.toString(),
                    target.toString(), "forced by test");
            }
            if (failReplacementMove
                    && source.getFileName().toString().endsWith(".publish")) {
                throw new IOException("forced fallback replacement failure");
            }
            Files.move(source, target, options);
        }

        @Override
        public OutputStream newOutputStream(Path path, OpenOption... options)
                throws IOException {
            List<OpenOption> requested = Arrays.asList(options);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    || !requested.contains(StandardOpenOption.CREATE_NEW)
                    || !requested.contains(StandardOpenOption.WRITE)
                    || !requested.contains(LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("publish temp was not opened with CREATE_NEW and NOFOLLOW");
            }
            OutputStream delegate = Files.newOutputStream(path, options);
            if (!cancelDuringPublishCopy
                    || !path.getFileName().toString().endsWith(".publish")) {
                return delegate;
            }
            return new FilterOutputStream(delegate) {
                private boolean cancelled;

                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    out.write(buffer, offset, length);
                    if (!cancelled) {
                        cancelled = true;
                        cancellation.cancel();
                    }
                }
            };
        }
    }
}
