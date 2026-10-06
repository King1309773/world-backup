package com.rlcmj.worldbackup.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SnapshotManifest {
    private LinkedHashMap<String, FileFingerprint> files;

    public SnapshotManifest() {
        this.files = new LinkedHashMap<>();
    }

    public SnapshotManifest(Map<String, FileFingerprint> files) {
        this();
        Objects.requireNonNull(files, "files").forEach(this::put);
    }

    public SnapshotManifest(SnapshotManifest source) {
        this(Objects.requireNonNull(source, "source").files);
    }

    public void put(String path, FileFingerprint fingerprint) {
        files.put(normalizePath(path), Objects.requireNonNull(fingerprint, "fingerprint"));
    }

    public FileFingerprint get(String path) {
        return files.get(normalizePath(path));
    }

    public Map<String, FileFingerprint> getFiles() {
        return Collections.unmodifiableMap(files);
    }

    public int size() {
        return files.size();
    }

    public static String normalizePath(String path) {
        return PortablePaths.normalizeRelativePath(path);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapshotManifest)) {
            return false;
        }
        SnapshotManifest that = (SnapshotManifest) other;
        return files.equals(that.files);
    }

    @Override
    public int hashCode() {
        return files.hashCode();
    }
}
