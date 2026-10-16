package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.core.BackupCancellation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Bounded channel transfers; the stager owns cleanup of any partial destination. */
final class SnapshotFileCopier {
    private static final long CHUNK_BYTES = 8L * 1024L * 1024L;
    private SnapshotFileCopier() { }

    static void copy(Path source, Path target, BackupCancellation cancellation)
            throws IOException {
        copy(source, target, cancellation,
            (input, offset, count, output) -> input.transferTo(offset, count, output));
    }

    static void copy(Path source, Path target, BackupCancellation cancellation,
                     Transfer transfer) throws IOException {
        cancellation.check();
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
             FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            long offset = 0L;
            ByteBuffer fallback = null;
            while (offset < size) {
                cancellation.check();
                long count = transfer.copy(input, offset, Math.min(CHUNK_BYTES, size - offset), output);
                if (count == 0L) {
                    // Some file systems do not implement direct transfer. Do not spin.
                    if (fallback == null) fallback = ByteBuffer.allocate(64 * 1024);
                    fallback.clear();
                    fallback.limit((int) Math.min(fallback.capacity(), size - offset));
                    input.position(offset);
                    int read = input.read(fallback);
                    if (read <= 0) throw new IOException("Snapshot source ended during copy");
                    fallback.flip();
                    while (fallback.hasRemaining()) {
                        cancellation.check();
                        if (output.write(fallback) == 0) {
                            throw new IOException("Snapshot destination made no progress");
                        }
                    }
                    count = read;
                }
                offset += count;
                cancellation.check();
            }
        }
        cancellation.check();
        Files.setLastModifiedTime(target,
            Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS));
    }

    @FunctionalInterface
    interface Transfer {
        long copy(FileChannel input, long offset, long count, FileChannel output)
            throws IOException;
    }
}
