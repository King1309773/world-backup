package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SnapshotDiffTest {
    @Test
    public void calculatesAddedChangedAndDeletedPaths() {
        SnapshotManifest before = manifest(
            file("a.dat", 1, 1, "a"), file("gone.dat", 1, 1, "g"));
        SnapshotManifest after = manifest(
            file("a.dat", 2, 2, "b"), file("new.dat", 1, 1, "n"));

        SnapshotDiff diff = SnapshotDiff.between(before, after);

        assertEquals(Arrays.asList("a.dat", "new.dat"), diff.getChangedPaths());
        assertEquals(Collections.singletonList("gone.dat"), diff.getDeletedPaths());
    }

    @Test
    public void ignoresUnchangedPathsEvenWhenMetadataDiffers() {
        SnapshotManifest before = manifest(file("same.dat", 1, 1, "same-hash"));
        SnapshotManifest after = manifest(file("same.dat", 9, 10, "same-hash"));

        SnapshotDiff diff = SnapshotDiff.between(before, after);

        assertEquals(Collections.emptyList(), diff.getChangedPaths());
        assertEquals(Collections.emptyList(), diff.getDeletedPaths());
    }

    @Test
    public void sortsResultsDeterministically() {
        SnapshotManifest before = manifest(
            file("z-old.dat", 1, 1, "z"), file("a-old.dat", 1, 1, "a"));
        SnapshotManifest after = manifest(
            file("z-new.dat", 1, 1, "z"), file("a-new.dat", 1, 1, "a"));

        SnapshotDiff diff = SnapshotDiff.between(before, after);

        assertEquals(Arrays.asList("a-new.dat", "z-new.dat"), diff.getChangedPaths());
        assertEquals(Arrays.asList("a-old.dat", "z-old.dat"), diff.getDeletedPaths());
    }

    @Test
    public void reportsChangedRegionAsOneWholeFile() {
        SnapshotDiff diff = SnapshotDiff.between(
            manifest(file("region/r.0.0.mca", 1, 1, "old")),
            manifest(file("region/r.0.0.mca", 2, 2, "new")));

        assertEquals(Collections.singletonList("region/r.0.0.mca"),
            diff.getChangedPaths());
    }

    @Test
    public void exposesUnmodifiableLists() {
        SnapshotDiff diff = SnapshotDiff.between(
            manifest(), manifest(file("new.dat", 1, 1, "new")));

        assertThrows(UnsupportedOperationException.class,
            () -> diff.getChangedPaths().add("other.dat"));
        assertThrows(UnsupportedOperationException.class,
            () -> diff.getDeletedPaths().add("other.dat"));
    }

    @Test
    public void rejectsNullManifestsClearly() {
        SnapshotManifest manifest = manifest();

        assertThrows(NullPointerException.class, () -> SnapshotDiff.between(null, manifest));
        assertThrows(NullPointerException.class, () -> SnapshotDiff.between(manifest, null));
    }

    private static SnapshotManifest manifest(Entry... entries) {
        SnapshotManifest manifest = new SnapshotManifest();
        for (Entry entry : entries) {
            manifest.put(entry.path, entry.fingerprint);
        }
        return manifest;
    }

    private static Entry file(String path, long size, long modifiedTimeMillis, String hash) {
        return new Entry(path, new FileFingerprint(size, modifiedTimeMillis, hash));
    }

    private static final class Entry {
        private final String path;
        private final FileFingerprint fingerprint;

        private Entry(String path, FileFingerprint fingerprint) {
            this.path = path;
            this.fingerprint = fingerprint;
        }
    }
}
