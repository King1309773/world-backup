package com.rlcmj.worldbackup.runtime;

/** Monotonic measurements of staging only, not a measurement of game frame time. */
public final class SnapshotMetrics {
    static final SnapshotMetrics EMPTY = new SnapshotMetrics(0, 0, 0, 0, 0, 0);
    private final long files;
    private final long bytes;
    private final long housekeepingNanos;
    private final long scanNanos;
    private final long copyNanos;
    private final long totalNanos;

    SnapshotMetrics(long files, long bytes, long housekeepingNanos,
                    long scanNanos, long copyNanos, long totalNanos) {
        this.files = files;
        this.bytes = bytes;
        this.housekeepingNanos = housekeepingNanos;
        this.scanNanos = scanNanos;
        this.copyNanos = copyNanos;
        this.totalNanos = totalNanos;
    }

    public long getFiles() { return files; }
    public long getBytes() { return bytes; }
    public long getHousekeepingNanos() { return housekeepingNanos; }
    public long getScanNanos() { return scanNanos; }
    public long getCopyNanos() { return copyNanos; }
    public long getTotalNanos() { return totalNanos; }
}
