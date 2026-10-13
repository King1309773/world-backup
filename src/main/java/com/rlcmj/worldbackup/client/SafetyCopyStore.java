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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SafetyCopyStore {
    private static final DateTimeFormatter SAFETY_TIMESTAMP = DateTimeFormatter
        .ofPattern("uuuuMMdd-HHmmss-SSS", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    public List<Path> list(Path gameDir, String worldId) throws BackupException {
        Path root = root(gameDir);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        requireDirectory(root);
        String safeWorld = safeBasename(worldId);
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (!isSafetyName(safeWorld, child.getFileName().toString())) continue;
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
        if (!isSafetyName(safeWorld, safeName)) throw unsafe(null);
        Path root = root(gameDir);
        requireDirectory(root);
        Path target = root.resolve(safeName).toAbsolutePath().normalize();
        if (!target.getParent().equals(root) || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw unsafe(null);
        }
        requireDirectory(target);
        List<Path> entries = validateTree(root, target);
        try {
            entries.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path entry : entries) Files.delete(entry);
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private static List<Path> validateTree(Path root, Path target)
            throws BackupException {
        List<Path> entries = new ArrayList<>();
        try {
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot) || realTarget.equals(realRoot)) {
                throw new IOException("safety copy escaped root");
            }
            Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,
                        BasicFileAttributes attrs) throws IOException {
                    validateEntry(dir, attrs, realRoot, realTarget, true);
                    entries.add(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    validateEntry(file, attrs, realRoot, realTarget, false);
                    entries.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file,
                        IOException failure) throws IOException {
                    throw failure;
                }
            });
            return entries;
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private static void validateEntry(Path entry, BasicFileAttributes attributes,
                                      Path realRoot, Path realTarget,
                                      boolean directory) throws IOException {
        Path normalized = entry.toAbsolutePath().normalize();
        if (!normalized.startsWith(realTarget) || !normalized.startsWith(realRoot)
                || Files.isSymbolicLink(entry) || attributes.isOther()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("unsafe safety copy entry");
        }
        Path real = entry.toRealPath();
        if (!real.startsWith(realTarget) || !real.startsWith(realRoot)) {
            throw new IOException("safety copy entry escaped root");
        }
    }

    private static boolean isSafetyName(String safeWorld, String name) {
        Matcher match = Pattern.compile("^" + Pattern.quote(safeWorld)
            + "-([0-9]{8}-[0-9]{6}-[0-9]{3})(?:-[1-9][0-9]{0,2})?$")
            .matcher(name);
        if (!match.matches()) return false;
        try {
            LocalDateTime.parse(match.group(1), SAFETY_TIMESTAMP);
            return true;
        } catch (DateTimeParseException invalidTimestamp) {
            return false;
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
