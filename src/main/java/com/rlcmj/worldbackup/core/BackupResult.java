package com.rlcmj.worldbackup.core;

import java.util.Objects;

public final class BackupResult {
    private final boolean success;
    private final String versionId;
    private final long elapsedMillis;
    private final long originalBytes;
    private final long archiveBytes;
    private final String errorKey;

    private BackupResult(boolean success, String versionId, long elapsedMillis,
                         long originalBytes, long archiveBytes, String errorKey) {
        this.success = success;
        this.versionId = success ? requireText(versionId, "versionId") : versionId;
        this.elapsedMillis = requireNonNegative(elapsedMillis, "elapsedMillis");
        this.originalBytes = requireNonNegative(originalBytes, "originalBytes");
        this.archiveBytes = requireNonNegative(archiveBytes, "archiveBytes");
        this.errorKey = success ? errorKey : requireText(errorKey, "errorKey");
    }

    public static BackupResult success(String versionId, long elapsedMillis,
                                       long originalBytes, long archiveBytes) {
        return new BackupResult(true, versionId, elapsedMillis, originalBytes, archiveBytes, null);
    }

    public static BackupResult failure(String errorKey, long elapsedMillis,
                                       long originalBytes, long archiveBytes) {
        return new BackupResult(false, null, elapsedMillis, originalBytes, archiveBytes,
            errorKey);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getVersionId() {
        return versionId;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public long getOriginalBytes() {
        return originalBytes;
    }

    public long getArchiveBytes() {
        return archiveBytes;
    }

    public String getErrorKey() {
        return errorKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BackupResult)) {
            return false;
        }
        BackupResult that = (BackupResult) other;
        return success == that.success
            && elapsedMillis == that.elapsedMillis
            && originalBytes == that.originalBytes
            && archiveBytes == that.archiveBytes
            && Objects.equals(versionId, that.versionId)
            && Objects.equals(errorKey, that.errorKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, versionId, elapsedMillis, originalBytes, archiveBytes,
            errorKey);
    }
}
