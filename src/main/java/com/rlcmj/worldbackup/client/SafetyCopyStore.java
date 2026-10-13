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
import java.nio.file.StandardCopyOption;
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
import java.util.UUID;
import java.util.Objects;

public final class SafetyCopyStore {
    private static final int WINDOWS_REPARSE_POINT = 0x400;
    private static final DateTimeFormatter SAFETY_TIMESTAMP = DateTimeFormatter
        .ofPattern("uuuuMMdd-HHmmss-SSS", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
    private final DosAttributeReader dosAttributes;
    private final BeforeCommit beforeCommit;
    private final IdSource ids;

    interface DosAttributeReader {
        Object read(Path path) throws IOException;
    }

    interface BeforeCommit { void run(Path target) throws IOException; }
    interface IdSource { String next(); }

    private static final class DirectoryAnchor {
        private final Path realPath;
        private final Object fileKey;
        private final java.nio.file.attribute.FileTime creationTime;
        private DirectoryAnchor(Path realPath, Object fileKey,
                                java.nio.file.attribute.FileTime creationTime) {
            this.realPath = realPath;
            this.fileKey = fileKey;
            this.creationTime = creationTime;
        }
    }

    public SafetyCopyStore() {
        this(path -> Files.getAttribute(path, "dos:attributes",
            LinkOption.NOFOLLOW_LINKS), target -> { }, () -> UUID.randomUUID().toString());
    }

    SafetyCopyStore(DosAttributeReader dosAttributes) {
        this(dosAttributes, target -> { }, () -> UUID.randomUUID().toString());
    }

    SafetyCopyStore(DosAttributeReader dosAttributes, BeforeCommit beforeCommit,
                    IdSource ids) {
        this.dosAttributes = dosAttributes;
        this.beforeCommit = beforeCommit;
        this.ids = ids;
    }

    public List<Path> list(Path gameDir, String worldId) throws BackupException {
        Path root = root(gameDir);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        requireSafetyRoot(gameDir, root);
        String safeWorld = safeBasename(worldId);
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (!isSafetyName(safeWorld, child.getFileName().toString())) continue;
                requireDirectSafeDirectory(root, child);
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
        requireSafetyRoot(gameDir, root);
        DirectoryAnchor rootAnchor = captureDirectory(root);
        Path target = root.resolve(safeName).toAbsolutePath().normalize();
        if (!target.getParent().equals(root) || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw unsafe(null);
        }
        requireDirectSafeDirectory(root, target);
        DirectoryAnchor targetAnchor = captureDirectory(target);
        validateTree(root, target, rootAnchor.realPath, targetAnchor.realPath);
        Path quarantine = root.resolve(".deleting-" + safeWorld + "-" + safeBasename(ids.next()))
            .toAbsolutePath().normalize();
        boolean moved = false;
        List<Path> entries;
        DirectoryAnchor quarantineAnchor = null;
        try {
            beforeCommit.run(target);
            // Commit point: re-check the direct child immediately before an atomic rename.
            // Java 8 on Windows has no usable SecureDirectoryStream, so hostile nested
            // swaps cannot be eliminated entirely; isolation plus per-node checks keeps
            // the remaining race window small and never follows a detected reparse point.
            requireSafetyRoot(gameDir, root);
            requireSameDirectory(root, rootAnchor);
            requireDirectSafeDirectory(root, target);
            requireSameDirectory(target, targetAnchor);
            if (!quarantine.getParent().equals(root)
                    || Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("unsafe quarantine path");
            }
            Files.move(target, quarantine, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            requireSameDirectory(root, rootAnchor);
            quarantineAnchor = captureDirectory(quarantine);
            if (!quarantineAnchor.realPath.getParent().equals(rootAnchor.realPath)) {
                throw new IOException("quarantine escaped root");
            }
            entries = validateTree(root, quarantine, rootAnchor.realPath,
                quarantineAnchor.realPath);
        } catch (BackupException failure) {
            if (moved) rollbackQuarantine(root, rootAnchor, quarantine, target, failure);
            throw failure;
        } catch (IOException | RuntimeException failure) {
            BackupException wrapped = unsafe(failure);
            if (moved) rollbackQuarantine(root, rootAnchor, quarantine, target, wrapped);
            throw wrapped;
        }
        final DirectoryAnchor isolatedAnchor = quarantineAnchor;
        try {
            entries.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path entry : entries) {
                requireSameDirectory(root, rootAnchor);
                requireSameDirectory(quarantine, isolatedAnchor);
                validateDeleteEntry(rootAnchor.realPath, isolatedAnchor.realPath, entry);
                Files.delete(entry);
            }
        } catch (IOException | RuntimeException failure) {
            // Partial deletion cannot be rolled back safely. The isolated remainder is
            // deliberately retained under .deleting-* for inspection/recovery.
            throw unsafe(failure);
        }
    }

    private void rollbackQuarantine(Path root, DirectoryAnchor rootAnchor, Path quarantine,
                                    Path target, BackupException failure) {
        try {
            requireSameDirectory(root, rootAnchor);
            if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectSafeDirectory(root, quarantine);
                Files.move(quarantine, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (BackupException | IOException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void validateDeleteEntry(Path realRoot, Path realTarget, Path entry)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(entry,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        validateEntry(entry, attributes, realRoot, realTarget,
            attributes.isDirectory());
    }

    private DirectoryAnchor captureDirectory(Path path) throws BackupException {
        requireDirectory(path);
        try {
            if (isWindowsReparsePoint(path)) throw new IOException("reparse directory");
            BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new DirectoryAnchor(path.toRealPath(), attributes.fileKey(),
                attributes.creationTime());
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private void requireSameDirectory(Path path, DirectoryAnchor expected)
            throws BackupException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isOther() || Files.isSymbolicLink(path)
                    || isWindowsReparsePoint(path)
                    || !expected.realPath.equals(path.toRealPath())
                    || (expected.fileKey != null
                        ? !Objects.equals(expected.fileKey, attributes.fileKey())
                        : !expected.creationTime.equals(attributes.creationTime()))) {
                throw new IOException("directory identity changed");
            }
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private void requireDirectSafeDirectory(Path root, Path target) throws BackupException {
        requireDirectory(target);
        try {
            if (isWindowsReparsePoint(target)) throw new IOException("reparse directory");
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realRoot.equals(realTarget.getParent())) {
                throw new IOException("directory escaped root");
            }
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private void requireSafetyRoot(Path gameDir, Path root) throws BackupException {
        requireDirectory(root);
        try {
            if (isWindowsReparsePoint(root)) throw new IOException("reparse safety root");
            Path realGame = gameDir.toAbsolutePath().normalize().toRealPath();
            Path realRoot = root.toRealPath();
            if (!realGame.equals(realRoot.getParent())) {
                throw new IOException("safety root escaped game directory");
            }
        } catch (IOException | RuntimeException failure) {
            throw unsafe(failure);
        }
    }

    private List<Path> validateTree(Path root, Path target, Path realRoot, Path realTarget)
            throws BackupException {
        List<Path> entries = new ArrayList<>();
        try {
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

    private void validateEntry(Path entry, BasicFileAttributes attributes,
                               Path realRoot, Path realTarget,
                               boolean directory) throws IOException {
        Path normalized = entry.toAbsolutePath().normalize();
        if (isWindowsReparsePoint(entry)
                || !normalized.startsWith(realTarget) || !normalized.startsWith(realRoot)
                || Files.isSymbolicLink(entry) || attributes.isOther()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("unsafe safety copy entry");
        }
        Path real = entry.toRealPath();
        if (!real.startsWith(realTarget) || !real.startsWith(realRoot)) {
            throw new IOException("safety copy entry escaped root");
        }
    }

    private boolean isWindowsReparsePoint(Path entry) throws IOException {
        try {
            Object flags = dosAttributes.read(entry);
            return flags instanceof Number
                && (((Number) flags).intValue() & WINDOWS_REPARSE_POINT) != 0;
        } catch (UnsupportedOperationException unsupported) {
            return false;
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
