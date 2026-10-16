package com.rlcmj.worldbackup.benchmark;

import java.io.OutputStream;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Random;

/** Alternates old and new stagers over the same generated files in one process. */
public final class SnapshotComparison {
    private static final String RUNTIME = "com.rlcmj.worldbackup.runtime.";

    public static void main(String[] args) throws Exception {
        Path base = Paths.get(args[0]).toAbsolutePath().normalize();
        Path sandbox = Files.createTempDirectory(base, "comparison-");
        try (IsolatedLoader old = new IsolatedLoader(Paths.get(args[1]).toUri().toURL());
             IsolatedLoader current = new IsolatedLoader(Paths.get(args[2]).toUri().toURL())) {
            Object oldStager = stager(old, sandbox.resolve("old"));
            Object newStager = stager(current, sandbox.resolve("new"));
            Class.forName(RUNTIME + "SnapshotTimings", true, current);
            run(sandbox, "large-files", 16, 64 * 1024 * 1024, 0, oldStager, newStager);
            run(sandbox, "many-small-files", 0, 0, 8192, oldStager, newStager);
            run(sandbox, "mixed", 64, 8 * 1024 * 1024, 2048, oldStager, newStager);
        } finally {
            delete(sandbox);
            System.out.println("comparison_cleaned=" + !Files.exists(sandbox));
        }
    }

    private static Object stager(ClassLoader loader, Path root) throws Exception {
        Constructor<?> constructor = loader.loadClass(RUNTIME +
            "WorldSnapshotController$NioSnapshotStager").getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(root);
    }

    private static void run(Path sandbox, String scenario, int largeCount,
                            int largeSize, int smallCount, Object old, Object current)
            throws Exception {
        Path source = Files.createDirectory(sandbox.resolve(scenario));
        byte[] buffer = new byte[1024 * 1024];
        new Random(42).nextBytes(buffer);
        for (int i = 0; i < largeCount; i++) {
            write(source.resolve("r." + i + ".mca"), largeSize, buffer);
        }
        for (int i = 0; i < smallCount; i++) {
            Path directory = source.resolve("data-" + i / 128);
            Files.createDirectories(directory);
            write(directory.resolve("file-" + i + ".dat"), 16 * 1024, buffer);
        }
        for (int round = 1; round <= 3; round++) {
            if (round % 2 == 1) {
                sample(scenario, round, "baseline", old, source);
                sample(scenario, round, "optimized", current, source);
            } else {
                sample(scenario, round, "optimized", current, source);
                sample(scenario, round, "baseline", old, source);
            }
        }
        delete(source);
    }

    private static void sample(String scenario, int round, String variant,
                               Object stager, Path source) throws Exception {
        Method stage = stager.getClass().getDeclaredMethod("stage", Path.class);
        stage.setAccessible(true);
        long start = System.nanoTime();
        Object snapshot;
        try { snapshot = stage.invoke(stager, source); }
        catch (InvocationTargetException failure) { throw new RuntimeException(failure.getCause()); }
        long elapsed = System.nanoTime() - start;
        Method close = snapshot.getClass().getMethod("close");
        close.setAccessible(true);
        long cleanupStart = System.nanoTime();
        close.invoke(snapshot);
        System.out.printf(Locale.ROOT, "scenario=%s round=%d variant=%s stage_ms=%.3f cleanup_ms=%.3f%n",
            scenario, round, variant, elapsed / 1e6, (System.nanoTime() - cleanupStart) / 1e6);
        System.out.flush();
    }

    private static void write(Path path, int size, byte[] buffer) throws IOException {
        try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            while (size > 0) {
                int count = Math.min(buffer.length, size);
                output.write(buffer, 0, count);
                size -= count;
            }
        }
    }

    private static void delete(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class IsolatedLoader extends URLClassLoader {
        IsolatedLoader(URL url) { super(new URL[] {url}, SnapshotComparison.class.getClassLoader()); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith(RUNTIME)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    try { found = findClass(name); }
                    catch (ClassNotFoundException absent) { return super.loadClass(name, resolve); }
                }
                if (resolve) resolveClass(found);
                return found;
            }
            return super.loadClass(name, resolve);
        }
    }
}
