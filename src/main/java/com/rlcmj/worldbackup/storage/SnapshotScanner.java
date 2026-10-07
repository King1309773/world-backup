package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.PortablePaths;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class SnapshotScanner {
    private static final int HASH_BUFFER_SIZE = 8192;

    private final FileHasher hasher;

    public SnapshotScanner() {
        this(streamingHasher(noFollowInputStreamFactory()));
    }

    SnapshotScanner(FileHasher hasher) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public SnapshotManifest scan(Path worldRoot, SnapshotManifest previous,
                                 Predicate<String> exclusion,
                                 BackupCancellation cancellation)
            throws BackupException, InterruptedIOException {
        Objects.requireNonNull(worldRoot, "worldRoot");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.check();

        Map<String, FileFingerprint> files = new TreeMap<>();
        try {
            Path resolvedRoot = worldRoot.toRealPath();
            Files.walkFileTree(resolvedRoot, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, visitor(resolvedRoot, previous, exclusion, cancellation, files));
        } catch (InterruptedIOException interrupted) {
            throw interrupted;
        } catch (PathEscapeIOException escape) {
            throw escape.error;
        } catch (IOException | RuntimeException failure) {
            throw new BackupException("worldbackup.error.scan", failure);
        }
        return new SnapshotManifest(files);
    }

    private FileVisitor<Path> visitor(Path resolvedRoot, SnapshotManifest previous,
                                      Predicate<String> exclusion,
                                      BackupCancellation cancellation,
                                      Map<String, FileFingerprint> files) {
        return new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes)
                    throws IOException {
                cancellation.check();
                if (!directory.equals(resolvedRoot)) {
                    String relativePath = portableRelativePath(resolvedRoot, directory);
                    Path realDirectory = directory.toRealPath();
                    try {
                        requireInsideWorld(resolvedRoot, realDirectory, relativePath);
                    } catch (BackupException escape) {
                        throw new PathEscapeIOException(escape);
                    }
                    if (exclusion != null && exclusion.test(relativePath)) {
                        FileState.validateContainment(directory, resolvedRoot, relativePath);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (attributes.isOther() || Files.isSymbolicLink(directory)
                            || !directory.toAbsolutePath().normalize().equals(realDirectory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                cancellation.check();
                String relativePath = portableRelativePath(resolvedRoot, file);
                BasicFileAttributes refreshedAttributes = Files.readAttributes(file,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Path contentPath = file;
                if (refreshedAttributes.isSymbolicLink()) {
                    contentPath = file.toRealPath();
                    try {
                        requireInsideWorld(resolvedRoot, contentPath, relativePath);
                    } catch (BackupException escape) {
                        throw new PathEscapeIOException(escape);
                    }
                } else if (attributes.isSymbolicLink()
                        || attributes.isRegularFile() && !sameObservedFile(
                            attributes, refreshedAttributes)) {
                    throw new IOException("File changed while scanning: " + relativePath);
                }
                if ("session.lock".equals(relativePath)) {
                    return FileVisitResult.CONTINUE;
                }
                if (exclusion != null && exclusion.test(relativePath)) {
                    FileState.validateContainment(file, resolvedRoot, relativePath);
                    return FileVisitResult.CONTINUE;
                }
                if (refreshedAttributes.isSymbolicLink()) {
                    BasicFileAttributes targetAttributes = Files.readAttributes(contentPath,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (targetAttributes.isDirectory()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!targetAttributes.isRegularFile()) {
                        throw new IOException("Symbolic link does not resolve to a regular file: "
                            + relativePath);
                    }
                } else if (!refreshedAttributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }

                FileState contentState = FileState.capture(contentPath, resolvedRoot,
                    relativePath);
                long size = contentState.size;
                long modifiedTimeMillis = contentState.modifiedTime.toMillis();
                FileFingerprint oldFingerprint = previous == null
                    ? null : previous.get(relativePath);
                String sha256;
                if (oldFingerprint != null && oldFingerprint.getSize() == size
                        && oldFingerprint.getModifiedTimeMillis() == modifiedTimeMillis) {
                    sha256 = oldFingerprint.getSha256();
                } else {
                    sha256 = hasher.hash(contentPath, contentState, cancellation);
                }
                contentState.verify(contentPath);
                if (refreshedAttributes.isSymbolicLink()) {
                    verifyLinkStillResolvesInside(file, contentPath, resolvedRoot, relativePath);
                }
                files.put(relativePath,
                    new FileFingerprint(size, modifiedTimeMillis, sha256));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                    throws IOException {
                cancellation.check();
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                cancellation.check();
                if (failure != null) {
                    throw failure;
                }
                return FileVisitResult.CONTINUE;
            }
        };
    }

    private static String portableRelativePath(Path root, Path entry) {
        return PortablePaths.normalizeRelativePath(root.relativize(entry).toString());
    }

    private static boolean sameObservedFile(BasicFileAttributes first,
                                            BasicFileAttributes second) {
        if (!first.isRegularFile() || !second.isRegularFile()
                || first.size() != second.size()
                || !first.lastModifiedTime().equals(second.lastModifiedTime())) {
            return false;
        }
        Object firstKey = first.fileKey();
        Object secondKey = second.fileKey();
        return firstKey == null || secondKey == null || firstKey.equals(secondKey);
    }

    private static void verifyLinkStillResolvesInside(Path link, Path expectedTarget,
                                                       Path resolvedRoot,
                                                       String relativePath)
            throws IOException {
        BasicFileAttributes linkAttributes = Files.readAttributes(link,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!linkAttributes.isSymbolicLink()) {
            throw new IOException("Symbolic link changed while scanning: " + relativePath);
        }
        Path currentTarget = link.toRealPath();
        try {
            requireInsideWorld(resolvedRoot, currentTarget, relativePath);
        } catch (BackupException escape) {
            throw new PathEscapeIOException(escape);
        }
        if (!currentTarget.equals(expectedTarget)) {
            throw new IOException("Symbolic link target changed while scanning: "
                + relativePath);
        }
    }

    static void requireInsideWorld(Path resolvedRoot, Path resolvedTarget, String relativePath)
            throws BackupException {
        Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        Objects.requireNonNull(resolvedTarget, "resolvedTarget");
        Objects.requireNonNull(relativePath, "relativePath");
        if (!resolvedTarget.normalize().startsWith(resolvedRoot.normalize())) {
            throw new BackupException("worldbackup.error.path_escape", relativePath);
        }
    }

    static FileHasher streamingHasher(InputStreamFactory streams) {
        Objects.requireNonNull(streams, "streams");
        return (path, expectedState, cancellation) -> {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IOException("SHA-256 is unavailable", unavailable);
            }

            byte[] buffer = new byte[HASH_BUFFER_SIZE];
            expectedState.verify(path);
            InputStream opened;
            try {
                opened = streams.open(path);
            } catch (IOException failure) {
                throw expectedState.classifyOpenFailure(path, failure);
            }
            try (InputStream input = opened) {
                expectedState.verify(path);
                while (true) {
                    cancellation.check();
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
                expectedState.verify(path);
            }
            return lowercaseHex(digest.digest());
        };
    }

    static InputStreamFactory noFollowInputStreamFactory() {
        return path -> Files.newInputStream(path, StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS);
    }

    private static String lowercaseHex(byte[] bytes) {
        char[] characters = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            characters[index * 2] = digits[value >>> 4];
            characters[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(characters);
    }

    interface FileHasher {
        String hash(Path path, FileState expectedState, BackupCancellation cancellation)
            throws IOException;
    }

    interface InputStreamFactory {
        InputStream open(Path path) throws IOException;
    }

    static final class FileState {
        private final Path realPath;
        private final long size;
        private final FileTime modifiedTime;
        private final Object fileKey;
        private final Path resolvedRoot;
        private final String relativePath;

        private FileState(Path realPath, BasicFileAttributes attributes,
                          Path resolvedRoot, String relativePath) {
            this.realPath = realPath;
            this.size = attributes.size();
            this.modifiedTime = attributes.lastModifiedTime();
            this.fileKey = attributes.fileKey();
            this.resolvedRoot = resolvedRoot;
            this.relativePath = relativePath;
        }

        private static FileState capture(Path path, Path resolvedRoot,
                                         String relativePath) throws IOException {
            Path beforeRealPath = resolveInsideWorld(path, resolvedRoot, relativePath);
            BasicFileAttributes before = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            requireLinkTargetInsideWorld(path, before, resolvedRoot, relativePath);
            if (!before.isRegularFile()) {
                throw new IOException("Hash target is not a regular file: " + path);
            }
            Path afterRealPath = resolveInsideWorld(path, resolvedRoot, relativePath);
            BasicFileAttributes after = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            requireLinkTargetInsideWorld(path, after, resolvedRoot, relativePath);
            if (!beforeRealPath.equals(afterRealPath) || !sameObservedFile(before, after)) {
                throw new IOException("File changed while inspecting: " + path);
            }
            return new FileState(afterRealPath, after, resolvedRoot, relativePath);
        }

        private void verify(Path path) throws IOException {
            FileState current = capture(path, resolvedRoot, relativePath);
            if (!realPath.equals(current.realPath)
                    || size != current.size
                    || !modifiedTime.equals(current.modifiedTime)
                    || fileKey != null && current.fileKey != null
                        && !fileKey.equals(current.fileKey)) {
                throw new IOException("File changed while hashing: " + path);
            }
        }

        private IOException classifyOpenFailure(Path path, IOException failure)
                throws PathEscapeIOException {
            try {
                validateContainment(path, resolvedRoot, relativePath);
            } catch (PathEscapeIOException escape) {
                throw escape;
            } catch (IOException inspectionFailure) {
                failure.addSuppressed(inspectionFailure);
            }
            return failure;
        }

        private static void validateContainment(Path path, Path resolvedRoot,
                                                String relativePath)
                throws IOException {
            resolveInsideWorld(path, resolvedRoot, relativePath);
            BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            requireLinkTargetInsideWorld(path, attributes, resolvedRoot, relativePath);
            requireInsideWorldForRead(resolvedRoot, path.toRealPath(), relativePath);
        }

        private static Path resolveInsideWorld(Path path, Path resolvedRoot,
                                               String relativePath)
                throws IOException {
            Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            requireInsideWorldForRead(resolvedRoot, realPath, relativePath);
            Path parent = path.getParent();
            if (parent != null) {
                requireInsideWorldForRead(resolvedRoot, parent.toRealPath(), relativePath);
            }
            return realPath;
        }

        private static void requireLinkTargetInsideWorld(Path path,
                                                         BasicFileAttributes attributes,
                                                         Path resolvedRoot,
                                                         String relativePath)
                throws IOException {
            if (attributes.isSymbolicLink()) {
                requireInsideWorldForRead(resolvedRoot, path.toRealPath(), relativePath);
            }
        }

        private static void requireInsideWorldForRead(Path resolvedRoot,
                                                      Path resolvedTarget,
                                                      String relativePath)
                throws PathEscapeIOException {
            try {
                requireInsideWorld(resolvedRoot, resolvedTarget, relativePath);
            } catch (BackupException escape) {
                throw new PathEscapeIOException(escape);
            }
        }
    }

    private static final class PathEscapeIOException extends IOException {
        private final BackupException error;

        private PathEscapeIOException(BackupException error) {
            super(error);
            this.error = error;
        }
    }
}
