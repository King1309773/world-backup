package com.rlcmj.worldbackup.core;

import java.util.Objects;

public final class FileFingerprint {
    private long size;
    private long modifiedTimeMillis;
    private String sha256;

    private FileFingerprint() { }

    public FileFingerprint(long size, long modifiedTimeMillis, String sha256) {
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        if (sha256.trim().isEmpty()) {
            throw new IllegalArgumentException("sha256 must not be blank");
        }
        this.size = size;
        this.modifiedTimeMillis = modifiedTimeMillis;
        this.sha256 = sha256;
    }

    public long getSize() {
        return size;
    }

    public long getModifiedTimeMillis() {
        return modifiedTimeMillis;
    }

    public String getSha256() {
        return sha256;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FileFingerprint)) {
            return false;
        }
        FileFingerprint that = (FileFingerprint) other;
        return size == that.size
            && modifiedTimeMillis == that.modifiedTimeMillis
            && sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(size, modifiedTimeMillis, sha256);
    }
}
