package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RetentionPlanner {
    public Plan plan(BackupCatalog source, int maxVisibleVersions) {
        Objects.requireNonNull(source, "source");
        if (maxVisibleVersions < 1) {
            throw new IllegalArgumentException("maxVisibleVersions must be at least 1");
        }

        List<IndexedVersion> newestFirst = indexed(source.getVersions());
        Collections.sort(newestFirst, Comparator
            .comparingLong((IndexedVersion value) -> value.version.getCreatedAt()).reversed()
            .thenComparing(Comparator.comparingInt(
                (IndexedVersion value) -> value.index).reversed()));

        Set<String> visibleIds = new LinkedHashSet<>();
        boolean overwriteSelected = false;
        for (IndexedVersion value : newestFirst) {
            BackupVersion version = value.version;
            if (!version.isVisible()) {
                continue;
            }
            if (version.getMode() == BackupMode.OVERWRITE) {
                if (overwriteSelected) {
                    continue;
                }
                overwriteSelected = true;
            }
            if (visibleIds.size() < maxVisibleVersions) {
                visibleIds.add(version.getId());
            }
        }

        return planKeepingVisibleIds(source, visibleIds);
    }

    public Plan planKeepingVisibleIds(BackupCatalog source, Set<String> visibleIds) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(visibleIds, "visibleIds");
        Set<String> selected = new LinkedHashSet<>(visibleIds);
        Map<String, BackupVersion> byId = new HashMap<>();
        for (BackupVersion version : source.getVersions()) {
            byId.put(version.getId(), version);
        }
        for (String id : selected) {
            BackupVersion version = byId.get(id);
            if (version == null || !version.isVisible()) {
                throw new IllegalArgumentException("unknown visible version: " + id);
            }
        }
        Set<String> reachable = new HashSet<>(selected);
        for (String visibleId : selected) {
            markParents(visibleId, byId, reachable);
        }

        BackupCatalog retained = BackupCatalog.empty(source.getWorldId(), source.getDisplayName());
        Set<String> retainedArchives = new HashSet<>();
        for (BackupVersion version : source.getVersions()) {
            if (reachable.contains(version.getId())) {
                retained.add(copyWithVisibility(version, selected.contains(version.getId())));
                retainedArchives.add(version.getArchiveName());
            }
        }
        List<String> archivesToDelete = new ArrayList<>();
        for (BackupVersion version : source.getVersions()) {
            if (!reachable.contains(version.getId())
                    && !retainedArchives.contains(version.getArchiveName())) {
                archivesToDelete.add(version.getArchiveName());
            }
        }
        return new Plan(retained, archivesToDelete);
    }

    private static List<IndexedVersion> indexed(List<BackupVersion> versions) {
        List<IndexedVersion> indexed = new ArrayList<>(versions.size());
        for (int index = 0; index < versions.size(); index++) {
            indexed.add(new IndexedVersion(index, versions.get(index)));
        }
        return indexed;
    }

    private static void markParents(String id, Map<String, BackupVersion> byId,
                                    Set<String> reachable) {
        Set<String> visited = new HashSet<>();
        String currentId = id;
        while (currentId != null && visited.add(currentId)) {
            BackupVersion current = byId.get(currentId);
            if (current == null) {
                return;
            }
            reachable.add(currentId);
            if (current.getMode() != BackupMode.INCREMENTAL) {
                return;
            }
            currentId = current.getParentId();
        }
    }

    private static BackupVersion copyWithVisibility(BackupVersion version, boolean visible) {
        return new BackupVersion(version.getId(), version.getCreatedAt(), version.getTrigger(),
            version.getMode(), version.getArchiveName(), version.getParentId(),
            version.getBaseId(), visible, version.getOriginalBytes(), version.getArchiveBytes(),
            version.getDurationMillis(), version.getManifest(), version.getChangedPaths(),
            version.getDeletedPaths());
    }

    public static final class Plan {
        private final BackupCatalog catalog;
        private final List<String> archiveNamesToDelete;

        private Plan(BackupCatalog catalog, List<String> archiveNamesToDelete) {
            this.catalog = catalog;
            this.archiveNamesToDelete = Collections.unmodifiableList(
                new ArrayList<>(archiveNamesToDelete));
        }

        public BackupCatalog getCatalog() {
            return catalog;
        }

        public List<String> getArchiveNamesToDelete() {
            return archiveNamesToDelete;
        }
    }

    private static final class IndexedVersion {
        private final int index;
        private final BackupVersion version;

        private IndexedVersion(int index, BackupVersion version) {
            this.index = index;
            this.version = version;
        }
    }
}
