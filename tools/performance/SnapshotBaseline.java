package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.core.BackupCancellation;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Random;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Diagnostic harness invoking the existing 1.0.0 stager; excluded from release code.
 * Run only against its own generated test data, never a user world.
 */
public final class SnapshotBaseline {
    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory(Paths.get(args[0]), "baseline-")
            .toAbsolutePath().normalize();
        System.out.println("sandbox=" + sandbox);
        try {
            run(sandbox, "large-files", 16, 64 * 1024 * 1024, 0, 0);
            run(sandbox, "many-small-files", 0, 0, 8192, 16 * 1024);
            run(sandbox, "mixed", 64, 8 * 1024 * 1024, 2048, 16 * 1024);
        } finally {
            deleteOwnedTree(sandbox);
            System.out.println("sandbox_cleaned=" + !Files.exists(sandbox));
        }
    }

    private static void run(Path sandbox, String name, int largeCount, int largeSize,
                            int smallCount, int smallSize) throws Exception {
        Path source = Files.createDirectory(sandbox.resolve(name));
        byte[] buffer = new byte[1024 * 1024];
        new Random(42).nextBytes(buffer);
        for (int i = 0; i < largeCount; i++) {
            write(source.resolve("r." + i + ".mca"), largeSize, buffer);
        }
        for (int i = 0; i < smallCount; i++) {
            Path parent = source.resolve("data-" + i / 128);
            Files.createDirectories(parent);
            write(parent.resolve("file-" + i + ".dat"), smallSize, buffer);
        }
        final Method copy = WorldSnapshotController.NioSnapshotStager.class
            .getDeclaredMethod("copyFile", Path.class, Path.class, BackupCancellation.class);
        copy.setAccessible(true);
        final Method inspect = WorldSnapshotController.NioSnapshotStager.class
            .getDeclaredMethod("inspectTree", Path.class, BackupCancellation.class);
        inspect.setAccessible(true);
        final AtomicLong copyNanos = new AtomicLong();
        WorldSnapshotController.FileCopier copier = new WorldSnapshotController.FileCopier() {
            @Override public void copy(Path from, Path to) throws java.io.IOException {
                long start = System.nanoTime();
                try {
                    copy.invoke(null, from, to, new BackupCancellation());
                } catch (Exception error) {
                    throw new java.io.IOException(error);
                } finally {
                    copyNanos.addAndGet(System.nanoTime() - start);
                }
            }
        };
        WorldSnapshotController.NioSnapshotStager stager =
            new WorldSnapshotController.NioSnapshotStager(sandbox.resolve("staging"),
                store -> store.getUsableSpace(), copier);
        long bytes = (long) largeCount * largeSize + (long) smallCount * smallSize;
        for (int iteration = 1; iteration <= 3; iteration++) {
            copyNanos.set(0);
            long start = System.nanoTime();
            WorldSnapshotController.StagedSnapshot result = stager.stage(source,
                new BackupCancellation());
            long stage = System.nanoTime() - start;
            start = System.nanoTime();
            result.close();
            long cleanup = System.nanoTime() - start;
            start = System.nanoTime();
            inspect.invoke(null, source.toRealPath(), new BackupCancellation());
            long scan = System.nanoTime() - start;
            System.out.printf(Locale.ROOT,
                "case=%s run=%d files=%d bytes=%d stage_ms=%.3f copy_ms=%.3f other_stage_ms=%.3f scan_probe_ms=%.3f cleanup_ms=%.3f%n",
                name, iteration, largeCount + smallCount, bytes,
                stage / 1e6, copyNanos.get() / 1e6,
                (stage - copyNanos.get()) / 1e6, scan / 1e6, cleanup / 1e6);
            System.out.flush();
        }
        deleteOwnedTree(source);
    }

    private static void write(Path file, int size, byte[] buffer) throws Exception {
        try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
            while (size > 0) {
                int count = Math.min(size, buffer.length);
                output.write(buffer, 0, count);
                size -= count;
            }
        }
    }

    private static void deleteOwnedTree(Path root) throws Exception {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws java.io.IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory,
                    java.io.IOException error) throws java.io.IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
