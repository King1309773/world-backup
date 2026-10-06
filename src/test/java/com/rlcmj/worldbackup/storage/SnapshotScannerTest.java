package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SnapshotScannerTest {
    private static final String HASH_A = repeat('a', 64);
    private static final String HASH_B = repeat('b', 64);

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void returnsNestedPathsWithForwardSlashesInStableOrder() throws Exception {
        Path world = temp.newFolder("ordered-world").toPath();
        write(world.resolve("z.dat"), "z");
        write(world.resolve("data/z.dat"), "data-z");
        write(world.resolve("data/a.dat"), "data-a");
        write(world.resolve("a.dat"), "a");

        SnapshotManifest manifest = scan(world);

        assertEquals(Arrays.asList("a.dat", "data/a.dat", "data/z.dat", "z.dat"),
            new ArrayList<>(manifest.getFiles().keySet()));
    }

    @Test
    public void excludesOnlyTheRootSessionLockByDefault() throws Exception {
        Path world = temp.newFolder("session-lock-world").toPath();
        write(world.resolve("session.lock"), "root lock");
        write(world.resolve("sub/session.lock"), "nested lock");

        SnapshotManifest manifest = scan(world);

        assertFalse(manifest.getFiles().containsKey("session.lock"));
        assertTrue(manifest.getFiles().containsKey("sub/session.lock"));
    }

    @Test
    public void rejectsRootSessionLockSymlinkEscapingWorld() throws Exception {
        Path world = temp.newFolder("escaping-session-lock-world").toPath();
        Path outside = temp.newFile("outside-session-lock.dat").toPath();
        Path sessionLock = world.resolve("session.lock");
        createSymbolicLinkOrSkip(sessionLock, outside);

        BackupException error = assertThrows(BackupException.class,
            () -> scan(world));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertEquals("session.lock", error.getArguments()[0]);
    }

    @Test
    public void excludesRootSessionLockSymlinkWhoseTargetIsInsideWorld() throws Exception {
        Path world = temp.newFolder("inside-session-lock-world").toPath();
        Path target = write(world.resolve("actual-lock.dat"), "inside");
        Path sessionLock = world.resolve("session.lock");
        createSymbolicLinkOrSkip(sessionLock, target.getFileName());

        SnapshotManifest manifest = scan(world);

        assertTrue(manifest.getFiles().containsKey("actual-lock.dat"));
        assertFalse(manifest.getFiles().containsKey("session.lock"));
    }

    @Test
    public void computesKnownLowercaseSha256OnFirstScan() throws Exception {
        Path world = temp.newFolder("hash-world").toPath();
        write(world.resolve("level.dat"), "hello");

        FileFingerprint fingerprint = scan(world).get("level.dat");

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            fingerprint.getSha256());
        assertTrue(fingerprint.getSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    public void reusesPreviousHashWhenPathSizeAndMtimeMatch() throws Exception {
        Path world = temp.newFolder("reuse-world").toPath();
        Path file = write(world.resolve("level.dat"), "same bytes");
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        SnapshotManifest previous = manifest("level.dat", size, modified, HASH_A);
        AtomicInteger hashes = new AtomicInteger();
        SnapshotScanner scanner = new SnapshotScanner((path, cancellation) -> {
            hashes.incrementAndGet();
            return HASH_B;
        });

        SnapshotManifest current = scanner.scan(world, previous, null,
            new BackupCancellation());

        assertEquals(HASH_A, current.get("level.dat").getSha256());
        assertEquals(0, hashes.get());
    }

    @Test
    public void recomputesHashWhenSizeDiffers() throws Exception {
        Path world = temp.newFolder("size-change-world").toPath();
        Path file = write(world.resolve("level.dat"), "bytes");
        long modified = Files.getLastModifiedTime(file).toMillis();
        AtomicInteger hashes = new AtomicInteger();
        SnapshotScanner scanner = countingScanner(hashes);
        SnapshotManifest previous = manifest("level.dat", Files.size(file) + 1L,
            modified, HASH_A);

        SnapshotManifest current = scanner.scan(world, previous, null,
            new BackupCancellation());

        assertEquals(HASH_B, current.get("level.dat").getSha256());
        assertEquals(1, hashes.get());
    }

    @Test
    public void recomputesHashWhenModifiedTimeDiffers() throws Exception {
        Path world = temp.newFolder("mtime-change-world").toPath();
        Path file = write(world.resolve("level.dat"), "bytes");
        long modified = Files.getLastModifiedTime(file).toMillis();
        AtomicInteger hashes = new AtomicInteger();
        SnapshotScanner scanner = countingScanner(hashes);
        SnapshotManifest previous = manifest("level.dat", Files.size(file),
            modified + 10000L, HASH_A);

        SnapshotManifest current = scanner.scan(world, previous, null,
            new BackupCancellation());

        assertEquals(HASH_B, current.get("level.dat").getSha256());
        assertEquals(1, hashes.get());
    }

    @Test
    public void appliesExclusionToNormalizedRelativePath() throws Exception {
        Path world = temp.newFolder("excluded-world").toPath();
        write(world.resolve("sub/excluded.dat"), "excluded");
        write(world.resolve("kept.dat"), "kept");
        List<String> pathsSeen = new ArrayList<>();

        SnapshotManifest manifest = new SnapshotScanner().scan(world, null, path -> {
            pathsSeen.add(path);
            return "sub/excluded.dat".equals(path);
        }, new BackupCancellation());

        Collections.sort(pathsSeen);
        assertEquals(Arrays.asList("kept.dat", "sub", "sub/excluded.dat"), pathsSeen);
        assertEquals(Collections.singletonList("kept.dat"),
            new ArrayList<>(manifest.getFiles().keySet()));
    }

    @Test
    public void excludedDirectorySkipsItsEntireSubtree() throws Exception {
        Path world = temp.newFolder("excluded-directory-world").toPath();
        write(world.resolve("excluded/nested/hidden.dat"), "hidden");
        write(world.resolve("kept.dat"), "kept");
        List<String> pathsSeen = new ArrayList<>();
        AtomicInteger hashes = new AtomicInteger();
        SnapshotScanner scanner = new SnapshotScanner((path, cancellation) -> {
            hashes.incrementAndGet();
            return HASH_B;
        });

        SnapshotManifest manifest = scanner.scan(world, null, path -> {
            pathsSeen.add(path);
            return "excluded".equals(path);
        }, new BackupCancellation());

        assertEquals(Collections.singletonList("kept.dat"),
            new ArrayList<>(manifest.getFiles().keySet()));
        assertTrue(pathsSeen.contains("excluded"));
        assertFalse(pathsSeen.contains("excluded/nested"));
        assertFalse(pathsSeen.contains("excluded/nested/hidden.dat"));
        assertEquals(1, hashes.get());
    }

    @Test
    public void cancellationBeforeScanPropagatesInterruptedIOException() throws Exception {
        Path world = temp.newFolder("cancelled-before-world").toPath();
        write(world.resolve("level.dat"), "bytes");
        BackupCancellation cancellation = new BackupCancellation();
        cancellation.cancel();

        assertThrows(InterruptedIOException.class,
            () -> new SnapshotScanner().scan(world, null, null, cancellation));
    }

    @Test
    public void cancellationDuringHashingIsCheckedBetweenBufferReads() throws Exception {
        Path world = temp.newFolder("cancelled-during-world").toPath();
        write(world.resolve("level.dat"), "ignored by injected stream");
        BackupCancellation cancellation = new BackupCancellation();
        AtomicInteger reads = new AtomicInteger();
        SnapshotScanner.FileHasher hasher = SnapshotScanner.streamingHasher(path ->
            new ByteArrayInputStream(new byte[9000]) {
                @Override
                public synchronized int read(byte[] bytes, int offset, int length) {
                    int result = super.read(bytes, offset, length);
                    if (reads.incrementAndGet() == 1) {
                        cancellation.cancel();
                    }
                    return result;
                }
            });

        assertThrows(InterruptedIOException.class,
            () -> new SnapshotScanner(hasher).scan(world, null, null, cancellation));
        assertEquals(1, reads.get());
    }

    @Test
    public void containmentValidationRejectsEscapingResolvedTarget() throws Exception {
        Path root = temp.newFolder("validation-root").toPath().toRealPath();
        Path outside = temp.newFile("outside.dat").toPath().toRealPath();

        BackupException error = assertThrows(BackupException.class,
            () -> SnapshotScanner.requireInsideWorld(root, outside, "escape.dat"));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertEquals("escape.dat", error.getArguments()[0]);
    }

    @Test
    public void rejectsEscapingSymbolicLink() throws Exception {
        Path world = temp.newFolder("escaping-link-world").toPath();
        Path outside = temp.newFile("outside-target.dat").toPath();
        Path link = world.resolve("escape.dat");
        createSymbolicLinkOrSkip(link, outside);

        BackupException error = assertThrows(BackupException.class,
            () -> scan(world));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertEquals("escape.dat", error.getArguments()[0]);
    }

    @Test
    public void hashesInRootSymbolicLinkAsTheLinkPath() throws Exception {
        Path world = temp.newFolder("inside-link-world").toPath();
        Path target = write(world.resolve("target.dat"), "hello");
        Path link = world.resolve("linked.dat");
        createSymbolicLinkOrSkip(link, target.getFileName());

        SnapshotManifest manifest = scan(world);

        assertEquals(manifest.get("target.dat").getSha256(),
            manifest.get("linked.dat").getSha256());
        assertEquals(Files.size(target), manifest.get("linked.dat").getSize());
    }

    @Test
    public void skipsInRootSymbolicLinkToDirectoryWithoutRecursing() throws Exception {
        Path world = temp.newFolder("directory-link-world").toPath();
        Path targetDirectory = Files.createDirectories(world.resolve("target"));
        write(targetDirectory.resolve("inside.dat"), "inside");
        Path link = world.resolve("linked-directory");
        createSymbolicLinkOrSkip(link, targetDirectory.getFileName());

        SnapshotManifest manifest = scan(world);

        assertTrue(manifest.getFiles().containsKey("target/inside.dat"));
        assertFalse(manifest.getFiles().containsKey("linked-directory/inside.dat"));
        assertFalse(manifest.getFiles().containsKey("linked-directory"));
    }

    @Test
    public void rejectsEscapingSymbolicLinkToDirectory() throws Exception {
        Path world = temp.newFolder("escaping-directory-link-world").toPath();
        Path outside = temp.newFolder("outside-directory-target").toPath();
        write(outside.resolve("secret.dat"), "outside");
        Path link = world.resolve("escape-directory");
        createSymbolicLinkOrSkip(link, outside);

        BackupException error = assertThrows(BackupException.class,
            () -> scan(world));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertEquals("escape-directory", error.getArguments()[0]);
    }

    @Test
    public void rejectsEscapingWindowsDirectoryJunction() throws Exception {
        Assume.assumeTrue("Windows junctions only", File.separatorChar == '\\');
        Path world = temp.newFolder("junction-world").toPath();
        Path outside = temp.newFolder("junction-target").toPath();
        write(outside.resolve("secret.dat"), "outside");
        Path junction = world.resolve("junction");
        createJunctionOrSkip(junction, outside);

        BackupException error = assertThrows(BackupException.class,
            () -> scan(world));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertEquals("junction", error.getArguments()[0]);
    }

    @Test
    public void skipsInRootWindowsDirectoryJunctionWithoutRecursing() throws Exception {
        Assume.assumeTrue("Windows junctions only", File.separatorChar == '\\');
        Path world = temp.newFolder("inside-junction-world").toPath();
        Path target = Files.createDirectories(world.resolve("target"));
        write(target.resolve("inside.dat"), "inside");
        Path junction = world.resolve("junction");
        createJunctionOrSkip(junction, target);

        SnapshotManifest manifest = scan(world);

        assertTrue(manifest.getFiles().containsKey("target/inside.dat"));
        assertFalse(manifest.getFiles().containsKey("junction/inside.dat"));
        assertFalse(manifest.getFiles().containsKey("junction"));
    }

    @Test
    public void mapsHashIoFailureToScanErrorWithCause() throws Exception {
        Path world = temp.newFolder("io-failure-world").toPath();
        write(world.resolve("level.dat"), "bytes");
        IOException failure = new IOException("simulated read failure");
        SnapshotScanner scanner = new SnapshotScanner((path, cancellation) -> {
            throw failure;
        });

        BackupException error = assertThrows(BackupException.class,
            () -> scanner.scan(world, null, null, new BackupCancellation()));

        assertEquals("worldbackup.error.scan", error.getTranslationKey());
        assertSame(failure, error.getCause());
    }

    private SnapshotManifest scan(Path world) throws BackupException, InterruptedIOException {
        return new SnapshotScanner().scan(world, null, null, new BackupCancellation());
    }

    private static SnapshotScanner countingScanner(AtomicInteger hashes) {
        return new SnapshotScanner((path, cancellation) -> {
            hashes.incrementAndGet();
            return HASH_B;
        });
    }

    private static SnapshotManifest manifest(String path, long size, long modified, String hash) {
        SnapshotManifest manifest = new SnapshotManifest();
        manifest.put(path, new FileFingerprint(size, modified, hash));
        return manifest;
    }

    private static Path write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, contents.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }
    }

    private static void createJunctionOrSkip(Path junction, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
            junction.toString(), target.toString()).redirectErrorStream(true).start();
        try (InputStream output = process.getInputStream()) {
            byte[] buffer = new byte[256];
            while (output.read(buffer) >= 0) {
                // Drain command output so the process cannot block.
            }
        }
        int exitCode = process.waitFor();
        Assume.assumeTrue("Directory junctions are unavailable", exitCode == 0);
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
