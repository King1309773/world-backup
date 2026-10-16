package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.core.BackupCancellation;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import static org.junit.Assert.*;

public class SnapshotPerformanceTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void reportsRealStageWorkAndPreservesContentsAndTimes() throws Exception {
        Path game = temp.newFolder().toPath();
        Path world = Files.createDirectories(game.resolve("saves/world/data"));
        byte[] payload = new byte[9 * 1024 * 1024 + 17];
        new java.util.Random(19).nextBytes(payload);
        Files.write(world.resolve("region.mca"), payload);
        Files.write(world.resolve("empty.dat"), new byte[0]);
        FileTime time = FileTime.fromMillis(1234567890000L);
        Files.setLastModifiedTime(world.resolve("region.mca"), time);
        Path source = world.getParent();
        Files.write(source.resolve("session.lock"), new byte[] {1});
        WorldSnapshotController.StagedSnapshot snapshot =
            new WorldSnapshotController.NioSnapshotStager(game.resolve("staging"))
                .stage(source);
        try {
            assertArrayEquals(payload, Files.readAllBytes(snapshot.root().resolve("data/region.mca")));
            assertEquals(0L, Files.size(snapshot.root().resolve("data/empty.dat")));
            assertEquals(time, Files.getLastModifiedTime(snapshot.root().resolve("data/region.mca")));
            SnapshotMetrics metrics = snapshot.metrics();
            assertEquals(2L, metrics.getFiles());
            assertEquals(payload.length, metrics.getBytes());
            assertTrue(metrics.getScanNanos() > 0L);
            assertTrue(metrics.getCopyNanos() > 0L);
            assertTrue(metrics.getTotalNanos() >= metrics.getScanNanos() + metrics.getCopyNanos());
        } finally {
            snapshot.close();
        }
    }

    @Test public void rejectsInternalJunctionBeforeCopyAndPreservesRealFiles() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Path game = temp.newFolder().toPath();
        Path world = Files.createDirectories(game.resolve("world"));
        Path real = Files.createDirectory(world.resolve("real"));
        Path canary = Files.write(real.resolve("keep.dat"), new byte[] {7});
        Path junction = world.resolve("alias");
        junction(junction, real);
        try {
            assertThrows(IOException.class, () ->
                new WorldSnapshotController.NioSnapshotStager(game.resolve("staging"))
                    .stage(world));
            assertArrayEquals(new byte[] {7}, Files.readAllBytes(canary));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test public void cancelledFileCopyDoesNotCreateTarget() throws Exception {
        Path game = temp.newFolder().toPath();
        Path source = Files.write(game.resolve("source.dat"), new byte[] {3});
        Path target = game.resolve("target.dat");
        BackupCancellation cancellation = new BackupCancellation();
        cancellation.cancel();
        assertThrows(java.io.InterruptedIOException.class,
            () -> SnapshotFileCopier.copy(source, target, cancellation));
        assertFalse(Files.exists(target));
    }

    @Test public void fileCopyNeverOverwritesExistingDestination() throws Exception {
        Path game = temp.newFolder().toPath();
        Path source = Files.write(game.resolve("source.dat"), new byte[] {3});
        Path target = Files.write(game.resolve("target.dat"), new byte[] {8});
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> SnapshotFileCopier.copy(source, target, new BackupCancellation()));
        assertArrayEquals(new byte[] {8}, Files.readAllBytes(target));
    }

    @Test public void zeroDirectTransferFallsBackWithoutLosingBytes() throws Exception {
        Path game = temp.newFolder().toPath();
        byte[] data = new byte[196613];
        new java.util.Random(71).nextBytes(data);
        Path source = Files.write(game.resolve("source.dat"), data);
        Path target = game.resolve("target.dat");
        SnapshotFileCopier.copy(source, target, new BackupCancellation(),
            (input, offset, count, output) -> 0L);
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test public void cancellationAfterFirstRealTransferCleansPartialStage() throws Exception {
        Path game = temp.newFolder().toPath();
        Path source = Files.createDirectory(game.resolve("world"));
        byte[] data = new byte[9 * 1024 * 1024 + 17];
        Files.write(source.resolve("region.mca"), data);
        BackupCancellation cancellation = new BackupCancellation();
        java.util.concurrent.atomic.AtomicInteger transfers = new java.util.concurrent.atomic.AtomicInteger();
        Path staging = game.resolve("staging");
        WorldSnapshotController.NioSnapshotStager stager =
            new WorldSnapshotController.NioSnapshotStager(staging,
                store -> Long.MAX_VALUE, (from, to) ->
                    SnapshotFileCopier.copy(from, to, cancellation,
                        (input, offset, count, output) -> {
                            long written = input.transferTo(offset, count, output);
                            assertTrue(written > 0L);
                            assertTrue(written <= 8L * 1024 * 1024);
                            transfers.incrementAndGet();
                            cancellation.cancel();
                            return written;
                        }));
        assertThrows(java.io.InterruptedIOException.class,
            () -> stager.stage(source, cancellation));
        assertEquals(1, transfers.get());
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(staging)) {
            assertFalse(entries.iterator().hasNext());
        }
        assertEquals(data.length, Files.size(source.resolve("region.mca")));
    }

    @Test public void shortTransfersContinueFromCorrectSourceOffset() throws Exception {
        Path game = temp.newFolder().toPath();
        byte[] data = new byte[131081];
        new java.util.Random(3).nextBytes(data);
        Path source = Files.write(game.resolve("source.dat"), data);
        Path target = game.resolve("target.dat");
        SnapshotFileCopier.copy(source, target, new BackupCancellation(),
            (input, offset, count, output) -> input.transferTo(offset, Math.min(317, count), output));
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test public void rejectsParentReplacedByJunctionDuringCopy() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Path game = temp.newFolder().toPath();
        Path world = Files.createDirectories(game.resolve("world"));
        Path nested = Files.createDirectory(world.resolve("nested"));
        Path original = Files.write(nested.resolve("file.dat"), new byte[] {1});
        Path outside = Files.createDirectory(game.resolve("outside"));
        Path canary = Files.write(outside.resolve("file.dat"), new byte[] {9});
        Path retained = world.resolve("retained");
        WorldSnapshotController.NioSnapshotStager stager =
            new WorldSnapshotController.NioSnapshotStager(game.resolve("staging"),
                store -> Long.MAX_VALUE, (from, to) -> {
                    Files.copy(from, to);
                    Files.move(nested, retained);
                    try { junction(nested, outside); }
                    catch (Exception failure) { throw new IOException(failure); }
                });
        try {
            assertThrows(IOException.class, () -> stager.stage(world, new BackupCancellation()));
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(canary));
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(retained.resolve(original.getFileName())));
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(game.resolve("staging"))) {
                assertFalse(entries.iterator().hasNext());
            }
        } finally {
            Files.deleteIfExists(nested);
        }
    }

    private static void junction(Path link, Path destination) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
            link.toString(), destination.toString()).redirectErrorStream(true).start();
        try (java.io.InputStream output = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            while (output.read(buffer) != -1) { }
        }
        if (process.waitFor() != 0) throw new IOException("Could not create test junction");
    }
}
