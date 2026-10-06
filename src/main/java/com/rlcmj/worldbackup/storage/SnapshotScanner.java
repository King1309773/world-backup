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
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
        this(streamingHasher(Files::newInputStream));
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
                    if (attributes.isOther() || Files.isSymbolicLink(directory)
                            || !directory.toAbsolutePath().normalize().equals(realDirectory)) {
                        throw new IOException(
                            "Linked directories are not supported: " + relativePath);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                cancellation.check();
                String relativePath = portableRelativePath(resolvedRoot, file);
                if ("session.lock".equals(relativePath)
                        || exclusion != null && exclusion.test(relativePath)) {
                    return FileVisitResult.CONTINUE;
                }

                Path contentPath = file;
                BasicFileAttributes contentAttributes = attributes;
                if (attributes.isSymbolicLink()) {
                    contentPath = file.toRealPath();
                    try {
                        requireInsideWorld(resolvedRoot, contentPath, relativePath);
                    } catch (BackupException escape) {
                        throw new PathEscapeIOException(escape);
                    }
                    contentAttributes = Files.readAttributes(contentPath,
                        BasicFileAttributes.class);
                    if (!contentAttributes.isRegularFile()) {
                        throw new IOException("Symbolic link does not resolve to a regular file: "
                            + relativePath);
                    }
                } else if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }

                long size = contentAttributes.size();
                long modifiedTimeMillis = contentAttributes.lastModifiedTime().toMillis();
                FileFingerprint oldFingerprint = previous == null
                    ? null : previous.get(relativePath);
                String sha256;
                if (oldFingerprint != null && oldFingerprint.getSize() == size
                        && oldFingerprint.getModifiedTimeMillis() == modifiedTimeMillis) {
                    sha256 = oldFingerprint.getSha256();
                } else {
                    sha256 = hasher.hash(contentPath, cancellation);
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
        return (path, cancellation) -> {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IOException("SHA-256 is unavailable", unavailable);
            }

            byte[] buffer = new byte[HASH_BUFFER_SIZE];
            try (InputStream input = streams.open(path)) {
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
            }
            return lowercaseHex(digest.digest());
        };
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
        String hash(Path path, BackupCancellation cancellation) throws IOException;
    }

    interface InputStreamFactory {
        InputStream open(Path path) throws IOException;
    }

    private static final class PathEscapeIOException extends IOException {
        private final BackupException error;

        private PathEscapeIOException(BackupException error) {
            super(error);
            this.error = error;
        }
    }
}
