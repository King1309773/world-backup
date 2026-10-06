package com.rlcmj.worldbackup.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class AtomicFiles {
    private AtomicFiles() { }

    @FunctionalInterface
    public interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

    public static void write(Path target, IOConsumer<OutputStream> writer) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(writer, "writer");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");

        try {
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writer.accept(output);
                output.flush();
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
