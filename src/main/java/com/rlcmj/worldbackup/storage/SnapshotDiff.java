package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class SnapshotDiff {
    private final List<String> changedPaths;
    private final List<String> deletedPaths;

    private SnapshotDiff(Set<String> changedPaths, Set<String> deletedPaths) {
        this.changedPaths = Collections.unmodifiableList(new ArrayList<>(changedPaths));
        this.deletedPaths = Collections.unmodifiableList(new ArrayList<>(deletedPaths));
    }

    public static SnapshotDiff between(SnapshotManifest previous, SnapshotManifest current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");

        Map<String, FileFingerprint> previousFiles = previous.getFiles();
        Map<String, FileFingerprint> currentFiles = current.getFiles();
        Set<String> changed = new TreeSet<>();
        Set<String> deleted = new TreeSet<>();

        for (Map.Entry<String, FileFingerprint> entry : currentFiles.entrySet()) {
            FileFingerprint oldFingerprint = previousFiles.get(entry.getKey());
            if (oldFingerprint == null || !oldFingerprint.getSha256().equals(
                    entry.getValue().getSha256())) {
                changed.add(entry.getKey());
            }
        }
        for (String path : previousFiles.keySet()) {
            if (!currentFiles.containsKey(path)) {
                deleted.add(path);
            }
        }

        return new SnapshotDiff(changed, deleted);
    }

    public List<String> getChangedPaths() {
        return changedPaths;
    }

    public List<String> getDeletedPaths() {
        return deletedPaths;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapshotDiff)) {
            return false;
        }
        SnapshotDiff that = (SnapshotDiff) other;
        return changedPaths.equals(that.changedPaths)
            && deletedPaths.equals(that.deletedPaths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(changedPaths, deletedPaths);
    }
}
