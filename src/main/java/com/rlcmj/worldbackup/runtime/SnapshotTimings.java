package com.rlcmj.worldbackup.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** A few phase records per backup; never emits per-file messages or world paths. */
final class SnapshotTimings {
    private static final Logger LOGGER = LogManager.getLogger("World Backup");
    private SnapshotTimings() { }

    static void phase(String phase, long startedNanos) {
        LOGGER.info("Snapshot phase={} elapsed_ms={}", phase,
            (System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    static void staged(SnapshotMetrics metrics) {
        LOGGER.info("Snapshot stage files={} bytes={} housekeeping_ms={} scan_ms={} copy_ms={} total_ms={}",
            metrics.getFiles(), metrics.getBytes(), metrics.getHousekeepingNanos() / 1_000_000.0,
            metrics.getScanNanos() / 1_000_000.0, metrics.getCopyNanos() / 1_000_000.0,
            metrics.getTotalNanos() / 1_000_000.0);
    }
}
