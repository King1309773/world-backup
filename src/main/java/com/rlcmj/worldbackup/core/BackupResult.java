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
        this.versionId = versionId;
        this.elapsedMillis = elapsedMillis;
        this.originalBytes = originalBytes;
        this.archiveBytes = archiveBytes;
        this.errorKey = errorKey;
    }

    public static BackupResult success(String versionId, long elapsedMillis,
                                       long originalBytes, long archiveBytes) {
        return new BackupResult(true, Objects.requireNonNull(versionId, "versionId"),
            elapsedMillis, originalBytes, archiveBytes, null);
    }

    public static BackupResult failure(String errorKey, long elapsedMillis,
                                       long originalBytes, long archiveBytes) {
        return new BackupResult(false, null, elapsedMillis, originalBytes, archiveBytes,
            Objects.requireNonNull(errorKey, "errorKey"));
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
