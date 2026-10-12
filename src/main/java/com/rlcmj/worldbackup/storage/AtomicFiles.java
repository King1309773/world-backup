package com.rlcmj.worldbackup.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AtomicFiles {
    private AtomicFiles() { }

    interface FileOperations {
        void move(Path source, Path target, CopyOption... options) throws IOException;

        void copy(Path source, Path target, CopyOption... options) throws IOException;

        boolean exists(Path path);

        boolean deleteIfExists(Path path) throws IOException;
    }

    private static final FileOperations NIO = new FileOperations() {
        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            Files.move(source, target, options);
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            Files.copy(source, target, options);
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }
    };

    @FunctionalInterface
    public interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

    @FunctionalInterface
    public interface IOAction {
        void run() throws IOException;
    }

    @FunctionalInterface
    public interface PublishGuard {
        void publish(IOAction publication) throws IOException;

        default void publish(IOAction publication, IOAction rollback) throws IOException {
            publish(publication);
        }

        default boolean supportsRollback() { return false; }
    }

    public static void write(Path target, IOConsumer<OutputStream> writer) throws IOException {
        write(target, writer, publication -> publication.run(), NIO);
    }

    public static void write(Path target, IOConsumer<OutputStream> writer,
                             PublishGuard publishGuard) throws IOException {
        write(target, writer, publishGuard, NIO);
    }

    static void write(Path target, IOConsumer<OutputStream> writer,
                      FileOperations operations) throws IOException {
        write(target, writer, publication -> publication.run(), operations);
    }

    static void write(Path target, IOConsumer<OutputStream> writer,
                      PublishGuard publishGuard, FileOperations operations) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(publishGuard, "publishGuard");
        Objects.requireNonNull(operations, "operations");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Path backup = target.resolveSibling(target.getFileName().toString() + ".bak");
        boolean originalExists = false;
        boolean publicationPrepared = false;
        AtomicBoolean rolledBack = new AtomicBoolean();

        try {
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                writer.accept(output);
                output.flush();
            }
            if (!publishGuard.supportsRollback()) {
                publishGuard.publish(() -> {
                    try {
                        operations.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        replaceNonAtomically(temp, target, operations);
                    }
                });
                return;
            }
            if (operations.exists(backup)) throw recoveryArtifactExists(backup);
            originalExists = operations.exists(target);
            if (originalExists) operations.copy(target, backup);
            publicationPrepared = true;
            final boolean restoreOriginal = originalExists;
            publishGuard.publish(() -> {
                try {
                    operations.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    operations.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }, () -> rollbackPublication(target, backup, restoreOriginal, rolledBack,
                operations));
            if (restoreOriginal) operations.deleteIfExists(backup);
        } catch (IOException | RuntimeException | Error failure) {
            if (publicationPrepared) {
                try {
                    rollbackPublication(target, backup, originalExists, rolledBack, operations);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            try {
                operations.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void rollbackPublication(Path target, Path backup,
                                            boolean originalExists,
                                            AtomicBoolean rolledBack,
                                            FileOperations operations)
            throws IOException {
        if (!rolledBack.compareAndSet(false, true)) return;
        try {
            if (originalExists) {
                if (operations.exists(backup)) {
                    operations.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                    operations.deleteIfExists(backup);
                }
            } else {
                operations.deleteIfExists(target);
            }
        } catch (IOException failure) {
            rolledBack.set(false);
            throw failure;
        }
    }

    private static void replaceNonAtomically(Path temp, Path target,
                                               FileOperations operations) throws IOException {
        Path backup = target.resolveSibling(target.getFileName().toString() + ".bak");
        boolean backupCreated = false;
        if (operations.exists(backup)) {
            throw recoveryArtifactExists(backup);
        }
        if (operations.exists(target)) {
            try {
                operations.copy(target, backup);
                backupCreated = true;
            } catch (IOException failure) {
                cleanup(backup, failure, operations);
                throw failure;
            }
        }

        try {
            operations.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            if (backupCreated) {
                restoreFromBackup(backup, target, failure, operations);
                backupCreated = false;
            } else {
                cleanup(target, failure, operations);
            }
            throw failure;
        }

        if (backupCreated) {
            try {
                operations.deleteIfExists(backup);
            } catch (IOException failure) {
                restoreFromBackup(backup, target, failure, operations);
                backupCreated = false;
                throw failure;
            }
        }
    }

    private static void cleanup(Path path, Throwable failure, FileOperations operations) {
        try {
            operations.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static IOException recoveryRequired(IOException failure,
                                                  IOException rollbackFailure, Path backup) {
        Path recoveryPath = backup.toAbsolutePath().normalize();
        IOException recovery = new IOException(failure.getMessage()
            + "; original target is preserved at " + recoveryPath, failure);
        recovery.addSuppressed(rollbackFailure);
        return recovery;
    }

    private static IOException recoveryArtifactExists(Path backup) {
        Path recoveryPath = backup.toAbsolutePath().normalize();
        return new IOException("Recovery artifact already exists at " + recoveryPath);
    }

    private static void restoreFromBackup(Path backup, Path target, IOException failure,
                                          FileOperations operations) throws IOException {
        try {
            operations.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException rollbackFailure) {
            throw recoveryRequired(failure, rollbackFailure, backup);
        }
        try {
            operations.deleteIfExists(backup);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            throw failure;
        }
    }
}
