package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RetentionPlannerTest {
    @Test
    public void keepsExactlyNewestTenVisibleAndDeletesUnreachableVersions() {
        BackupCatalog catalog = BackupCatalog.empty("World1", "My World");
        for (int index = 0; index < 12; index++) {
            catalog.add(full("v" + index, index, BackupMode.FULL));
        }

        RetentionPlanner.Plan plan = new RetentionPlanner().plan(catalog, 10);

        assertEquals(10, plan.getCatalog().getVersions().size());
        assertEquals(10, plan.getCatalog().getVersions().stream()
            .filter(BackupVersion::isVisible).count());
        assertEquals(new HashSet<>(Arrays.asList("v0-full.zip", "v1-full.zip")),
            new HashSet<>(plan.getArchiveNamesToDelete()));
        assertFalse(ids(plan).contains("v0"));
        assertFalse(ids(plan).contains("v1"));
    }

    @Test
    public void keepsOlderBaseAndIntermediateAsHiddenDependencies() {
        BackupCatalog catalog = BackupCatalog.empty("World1", "My World");
        catalog.add(full("base", 1L, BackupMode.FULL));
        catalog.add(delta("d1", 2L, "base", "base", true));
        catalog.add(delta("d2", 3L, "d1", "base", true));
        catalog.add(full("orphan", 0L, BackupMode.FULL));

        RetentionPlanner.Plan plan = new RetentionPlanner().plan(catalog, 1);

        assertEquals(new HashSet<>(Arrays.asList("base", "d1", "d2")), ids(plan));
        assertFalse(find(plan, "base").isVisible());
        assertFalse(find(plan, "d1").isVisible());
        assertTrue(find(plan, "d2").isVisible());
        assertEquals(Collections.singletonList("orphan-full.zip"),
            plan.getArchiveNamesToDelete());
    }

    @Test
    public void keepsOnlyNewestOverwriteAndStillAppliesGlobalVisibleLimit() {
        BackupCatalog catalog = BackupCatalog.empty("World1", "My World");
        catalog.add(full("ordinary-old", 1L, BackupMode.FULL));
        catalog.add(full("overwrite-old", 2L, BackupMode.OVERWRITE));
        catalog.add(full("ordinary-new", 3L, BackupMode.FULL));
        catalog.add(full("overwrite-new", 4L, BackupMode.OVERWRITE));

        RetentionPlanner.Plan plan = new RetentionPlanner().plan(catalog, 2);

        assertEquals(new HashSet<>(Arrays.asList("ordinary-new", "overwrite-new")), ids(plan));
        assertEquals(2, plan.getCatalog().getVersions().stream()
            .filter(BackupVersion::isVisible).count());
        assertEquals(new HashSet<>(Arrays.asList(
            "ordinary-old-full.zip", "overwrite-old-latest.zip")),
            new HashSet<>(plan.getArchiveNamesToDelete()));
    }

    @Test
    public void malformedFullDoesNotRetainItsUnrelatedParent() {
        BackupCatalog catalog = BackupCatalog.empty("World1", "My World");
        catalog.add(full("unrelated", 1L, BackupMode.FULL));
        catalog.add(independentWithParent("malformed-full", 2L, BackupMode.FULL,
            "unrelated"));

        RetentionPlanner.Plan plan = new RetentionPlanner().plan(catalog, 1);

        assertEquals(Collections.singleton("malformed-full"), ids(plan));
        assertEquals(Collections.singletonList("unrelated-full.zip"),
            plan.getArchiveNamesToDelete());
    }

    @Test
    public void malformedOverwriteDoesNotRetainItsUnrelatedParent() {
        BackupCatalog catalog = BackupCatalog.empty("World1", "My World");
        catalog.add(full("unrelated", 1L, BackupMode.FULL));
        catalog.add(independentWithParent("malformed-overwrite", 2L,
            BackupMode.OVERWRITE, "unrelated"));

        RetentionPlanner.Plan plan = new RetentionPlanner().plan(catalog, 1);

        assertEquals(Collections.singleton("malformed-overwrite"), ids(plan));
        assertEquals(Collections.singletonList("unrelated-full.zip"),
            plan.getArchiveNamesToDelete());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveVisibleLimit() {
        new RetentionPlanner().plan(BackupCatalog.empty("World1", "My World"), 0);
    }

    @Test
    public void removingOneVisibleVersionKeepsEveryOtherVisibleDependencyChain() {
        BackupCatalog catalog = BackupCatalog.empty("World1", "My World");
        catalog.add(full("base", 1L, BackupMode.FULL));
        catalog.add(delta("d1", 2L, "base", "base", false));
        catalog.add(delta("remove", 3L, "d1", "base", true));
        catalog.add(delta("keep", 4L, "d1", "base", true));
        catalog.add(full("other", 5L, BackupMode.FULL));

        RetentionPlanner.Plan plan = new RetentionPlanner().planKeepingVisibleIds(
            catalog, new HashSet<>(Arrays.asList("keep", "other")));

        assertEquals(new HashSet<>(Arrays.asList("base", "d1", "keep", "other")),
            ids(plan));
        assertEquals(Collections.singletonList("remove-delta.zip"),
            plan.getArchiveNamesToDelete());
        assertTrue(find(plan, "keep").isVisible());
        assertFalse(find(plan, "d1").isVisible());
    }

    private static BackupVersion full(String id, long time, BackupMode mode) {
        String suffix = mode == BackupMode.OVERWRITE ? "-latest.zip" : "-full.zip";
        return new BackupVersion(id, time, BackupTrigger.MANUAL, mode, id + suffix,
            null, null, true, 1L, 1L, 1L, new SnapshotManifest(),
            Collections.emptyList(), Collections.emptyList());
    }

    private static BackupVersion delta(String id, long time, String parent, String base,
                                       boolean visible) {
        return new BackupVersion(id, time, BackupTrigger.MANUAL, BackupMode.INCREMENTAL,
            id + "-delta.zip", parent, base, visible, 1L, 1L, 1L,
            new SnapshotManifest(), Collections.emptyList(), Collections.emptyList());
    }

    private static BackupVersion independentWithParent(String id, long time,
                                                       BackupMode mode, String parent) {
        String suffix = mode == BackupMode.OVERWRITE ? "-latest.zip" : "-full.zip";
        return new BackupVersion(id, time, BackupTrigger.MANUAL, mode, id + suffix,
            parent, null, true, 1L, 1L, 1L, new SnapshotManifest(),
            Collections.emptyList(), Collections.emptyList());
    }

    private static Set<String> ids(RetentionPlanner.Plan plan) {
        return plan.getCatalog().getVersions().stream().map(BackupVersion::getId)
            .collect(Collectors.toSet());
    }

    private static BackupVersion find(RetentionPlanner.Plan plan, String id) {
        return plan.getCatalog().getVersions().stream()
            .filter(version -> id.equals(version.getId())).findFirst().get();
    }
}
