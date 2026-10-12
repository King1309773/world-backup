package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.PortablePaths;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class SafetyCopyStore {
    public List<Path> list(Path gameDir, String worldId) throws BackupException {
        Path root = root(gameDir);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        requireDirectory(root);
        String prefix = safeBasename(worldId) + "-";
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (!child.getFileName().toString().startsWith(prefix)) continue;
                requireDirectory(child);
                result.add(child.toAbsolutePath().normalize());
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
        result.sort(Comparator.comparing((Path value) -> value.getFileName().toString())
            .reversed());
        return Collections.unmodifiableList(result);
    }

    public void delete(Path gameDir, String worldId, String copyName) throws BackupException {
        String safeWorld = safeBasename(worldId);
        String safeName = safeBasename(copyName);
        if (!safeName.startsWith(safeWorld + "-")) throw unsafe(null);
        Path root = root(gameDir);
        requireDirectory(root);
        Path target = root.resolve(safeName).toAbsolutePath().normalize();
        if (!target.getParent().equals(root) || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw unsafe(null);
        }
        requireDirectory(target);
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,
                        BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(dir) || attrs.isOther()) {
                        throw new IOException("linked safety directory");
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file) || attrs.isOther()) {
                        throw new IOException("linked safety file");
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir,
                        IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private static Path root(Path gameDir) throws BackupException {
        try {
            Path game = gameDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(game, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(game)) throw unsafe(null);
            Path root = game.resolve("restore-safety").normalize();
            if (!root.getParent().equals(game)) throw unsafe(null);
            return root;
        } catch (RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private static void requireDirectory(Path path) throws BackupException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isOther()
                    || Files.isSymbolicLink(path)) throw unsafe(null);
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private static BackupException unsafe(Throwable cause) {
        return cause == null ? new BackupException("worldbackup.error.path_escape")
            : new BackupException("worldbackup.error.path_escape", cause);
    }

    private static String safeBasename(String value) throws BackupException {
        try {
            return PortablePaths.requireSafeBasename(value);
        } catch (RuntimeException failure) {
            throw unsafe(failure);
        }
    }
}
