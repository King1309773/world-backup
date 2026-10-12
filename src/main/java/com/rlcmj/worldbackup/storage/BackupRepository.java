package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupResult;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.PortablePaths;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class BackupRepository {
    private static final long SPACE_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_MANAGED_TIMESTAMP_MILLIS = 253_402_300_799_999L;
    private static final Object BACKUP_LOCK = new Object();
    private static final DateTimeFormatter UTC_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern MANAGED_ARCHIVE_NAME = Pattern.compile(
        "[0-9]{8}-[0-9]{6}-[0-9]{3}-[0-9a-f]{8}-(full|delta|latest)\\.zip");

    private final ArchiveStore archiveStore;
    private final CatalogStore catalogStore;
    private final SnapshotScanner scanner;
    private final RetentionPlanner retentionPlanner;
    private final SpaceProbe spaceProbe;
    private final Clock clock;
    private final IdSource idSource;

    public BackupRepository() {
        SecureRandom random = new SecureRandom();
        this.archiveStore = new ArchiveStore();
        this.catalogStore = new CatalogStore();
        this.scanner = new SnapshotScanner();
        this.retentionPlanner = new RetentionPlanner();
        this.spaceProbe = path -> {
            FileStore store = Files.getFileStore(path);
            return store.getUsableSpace();
        };
        this.clock = System::currentTimeMillis;
        this.idSource = () -> String.format(Locale.ROOT, "%08x", random.nextInt());
    }

    BackupRepository(ArchiveStore archiveStore, CatalogStore catalogStore,
                     SnapshotScanner scanner, RetentionPlanner retentionPlanner,
                     SpaceProbe spaceProbe, Clock clock, IdSource idSource) {
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.catalogStore = Objects.requireNonNull(catalogStore, "catalogStore");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.retentionPlanner = Objects.requireNonNull(retentionPlanner, "retentionPlanner");
        this.spaceProbe = Objects.requireNonNull(spaceProbe, "spaceProbe");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSource = Objects.requireNonNull(idSource, "idSource");
    }

    public synchronized BackupResult createBackup(Path worldRoot, Path repoRoot,
                                                   String worldId, String displayName,
                                                   BackupMode mode, BackupTrigger trigger,
                                                   int maxVisibleVersions,
                                                   int incrementsPerBase,
                                                   long startedAtMillis)
            throws BackupException {
        return createBackup(worldRoot, repoRoot, worldId, displayName, mode, trigger,
            maxVisibleVersions, incrementsPerBase, startedAtMillis,
            new BackupCancellation());
    }

    public synchronized BackupResult createBackup(Path worldRoot, Path repoRoot,
                                                   String worldId, String displayName,
                                                   BackupMode mode, BackupTrigger trigger,
                                                   int maxVisibleVersions,
                                                   int incrementsPerBase,
                                                   long startedAtMillis,
                                                   BackupCancellation cancellation)
            throws BackupException {
        synchronized (BACKUP_LOCK) {
            return createBackupLocked(worldRoot, repoRoot, worldId, displayName, mode,
                trigger, maxVisibleVersions, incrementsPerBase, startedAtMillis,
                cancellation);
        }
    }

    private BackupResult createBackupLocked(Path worldRoot, Path repoRoot,
                                             String worldId, String displayName,
                                             BackupMode mode, BackupTrigger trigger,
                                             int maxVisibleVersions,
                                             int incrementsPerBase,
                                             long startedAtMillis,
                                             BackupCancellation cancellation)
            throws BackupException {
        validateArguments(worldRoot, repoRoot, worldId, displayName, mode, trigger,
            maxVisibleVersions, incrementsPerBase, startedAtMillis, cancellation);

        Path world = requireWorldRoot(worldRoot);
        Path repository = requireRepositoryRoot(repoRoot, world);
        BackupCatalog current = loadOrCreate(repository, worldId, displayName);
        validateIdentity(current, worldId);
        cleanupUnreferencedArchives(repository, current, Collections.emptyList());

        ParentState parent = mode == BackupMode.INCREMENTAL
            ? findParent(repository, current, incrementsPerBase) : ParentState.none();
        SnapshotManifest snapshot;
        try {
            snapshot = scanner.scan(world, parent.manifest, null, cancellation);
        } catch (InterruptedIOException failure) {
            throw new BackupException("worldbackup.error.cancelled", failure);
        }

        Creation creation = planCreation(mode, parent, snapshot);
        long includedBytes = sumIncludedBytes(snapshot, creation.includedPaths);
        requireSpace(repository, includedBytes);

        long createdAt = nextCreatedAt(current, clock.currentTimeMillis());
        String id = uniqueId(current, createdAt, repository);
        String archiveName = id + creation.archiveSuffix;
        Path archive = null;
        boolean catalogSaved = false;
        try {
            archive = archiveStore.write(repository, archiveName, world, snapshot,
                creation.includedPaths, creation.deletedPaths, cancellation);
            checkCancellation(cancellation);
            long archiveBytes = Files.size(archive);
            long duration = Math.max(0L, clock.currentTimeMillis() - startedAtMillis);
            long originalBytes = sumManifestBytes(snapshot);
            BackupVersion created = new BackupVersion(id, createdAt, trigger,
                creation.effectiveMode, archiveName, creation.parentId, creation.baseId,
                true, originalBytes, archiveBytes, duration, snapshot,
                creation.changedPaths, creation.deletedPaths);

            BackupCatalog candidate = appendCandidate(current, displayName, created);
            RetentionPlanner.Plan retention = retentionPlanner.plan(candidate,
                maxVisibleVersions);
            catalogStore.save(repository, retention.getCatalog(), cancellation);
            catalogSaved = true;
            cleanupUnreferencedArchives(repository, retention.getCatalog(),
                retention.getArchiveNamesToDelete());
            return BackupResult.success(id, duration, originalBytes, archiveBytes);
        } catch (BackupException failure) {
            if (!catalogSaved && archive != null) {
                cleanupNewArchive(archive, failure);
            }
            throw failure;
        } catch (IOException failure) {
            BackupException wrapped = new BackupException("worldbackup.error.archive_write",
                failure);
            if (!catalogSaved && archive != null) {
                cleanupNewArchive(archive, wrapped);
            }
            throw wrapped;
        } catch (RuntimeException failure) {
            BackupException wrapped = new BackupException("worldbackup.error.archive_write",
                failure);
            if (!catalogSaved && archive != null) {
                cleanupNewArchive(archive, wrapped);
            }
            throw wrapped;
        }
    }

    private BackupCatalog loadOrCreate(Path repository, String worldId, String displayName)
            throws BackupException {
        Path index = repository.resolve("index.json");
        return Files.exists(index, LinkOption.NOFOLLOW_LINKS)
            ? catalogStore.load(repository) : BackupCatalog.empty(worldId, displayName);
    }

    private ParentState findParent(Path repository, BackupCatalog catalog,
                                   int incrementsPerBase) {
        BackupVersion latest = latest(catalog.getVersions());
        if (latest == null || latest.getMode() == BackupMode.OVERWRITE) {
            return ParentState.none();
        }
        try {
            Path archive = resolveArchive(repository, latest.getArchiveName());
            SnapshotManifest manifest = archiveStore.readManifest(archive);
            if (!manifest.equals(latest.getManifest())) {
                return ParentState.none();
            }
            ChainState chain = inspectChain(repository, latest, catalog.getVersions());
            if (!chain.compatible || chain.deltaCount >= incrementsPerBase) {
                return ParentState.none();
            }
            return new ParentState(latest, manifest, chain.baseId);
        } catch (BackupException | RuntimeException failure) {
            return ParentState.none();
        }
    }

    private ChainState inspectChain(Path repository, BackupVersion latest,
                                    List<BackupVersion> versions) {
        Map<String, BackupVersion> byId = new HashMap<>();
        for (BackupVersion version : versions) {
            byId.put(version.getId(), version);
        }
        int deltas = 0;
        Set<String> visited = new HashSet<>();
        BackupVersion current = latest;
        while (current != null && visited.add(current.getId())) {
            try {
                SnapshotManifest archived = archiveStore.readManifest(
                    resolveArchive(repository, current.getArchiveName()));
                if (!archived.equals(current.getManifest())) {
                    return ChainState.incompatible();
                }
            } catch (BackupException | RuntimeException failure) {
                return ChainState.incompatible();
            }
            if (current.getMode() == BackupMode.FULL) {
                return new ChainState(true, deltas, current.getId());
            }
            if (current.getMode() != BackupMode.INCREMENTAL
                    || current.getParentId() == null) {
                return ChainState.incompatible();
            }
            deltas++;
            current = byId.get(current.getParentId());
        }
        return ChainState.incompatible();
    }

    private static Creation planCreation(BackupMode requested, ParentState parent,
                                         SnapshotManifest snapshot) {
        if (requested == BackupMode.OVERWRITE) {
            return Creation.independent(BackupMode.OVERWRITE, "-latest.zip", snapshot);
        }
        if (requested == BackupMode.FULL || parent.version == null) {
            return Creation.independent(BackupMode.FULL, "-full.zip", snapshot);
        }
        SnapshotDiff diff = SnapshotDiff.between(parent.manifest, snapshot);
        return new Creation(BackupMode.INCREMENTAL, "-delta.zip", parent.version.getId(),
            parent.baseId, diff.getChangedPaths(), diff.getChangedPaths(),
            diff.getDeletedPaths());
    }

    private static BackupCatalog appendCandidate(BackupCatalog current,
                                                 String displayName,
                                                 BackupVersion created) {
        BackupCatalog candidate = BackupCatalog.empty(current.getWorldId(), displayName);
        for (BackupVersion existing : current.getVersions()) {
            candidate.add(existing);
        }
        candidate.add(created);
        return candidate;
    }

    private void requireSpace(Path repository, long includedBytes) throws BackupException {
        long required = includedBytes > Long.MAX_VALUE - SPACE_RESERVE_BYTES
            ? Long.MAX_VALUE : includedBytes + SPACE_RESERVE_BYTES;
        try {
            if (spaceProbe.usableSpace(repository) <= required) {
                throw new BackupException("worldbackup.error.disk_space");
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.disk_space", failure);
        }
    }

    private String uniqueId(BackupCatalog catalog, long createdAt, Path repository)
            throws BackupException {
        Set<String> existingIds = new HashSet<>();
        Set<String> existingArchives = new HashSet<>();
        for (BackupVersion version : catalog.getVersions()) {
            existingIds.add(version.getId());
            existingArchives.add(version.getArchiveName());
        }
        String timestamp = UTC_TIMESTAMP.format(Instant.ofEpochMilli(createdAt));
        for (int attempt = 0; attempt < 100; attempt++) {
            String random = Objects.requireNonNull(idSource.nextEightHex(), "id")
                .toLowerCase(Locale.ROOT);
            if (!random.matches("[0-9a-f]{8}")) {
                throw new IllegalArgumentException("id source must return eight hex characters");
            }
            String id = timestamp + "-" + random;
            if (!existingIds.contains(id)
                    && !existingArchives.contains(id + "-full.zip")
                    && !existingArchives.contains(id + "-delta.zip")
                    && !existingArchives.contains(id + "-latest.zip")
                    && !Files.exists(repository.resolve(id + "-full.zip"),
                        LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(repository.resolve(id + "-delta.zip"),
                        LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(repository.resolve(id + "-latest.zip"),
                        LinkOption.NOFOLLOW_LINKS)) {
                return id;
            }
        }
        throw new BackupException("worldbackup.error.archive_write");
    }

    private static long nextCreatedAt(BackupCatalog catalog, long observedTime) {
        requireManagedTimestamp(observedTime, "createdAt");
        BackupVersion latest = latest(catalog.getVersions());
        if (latest == null || observedTime > latest.getCreatedAt()) {
            return observedTime;
        }
        if (latest.getCreatedAt() >= MAX_MANAGED_TIMESTAMP_MILLIS) {
            throw new IllegalArgumentException(
                "createdAt cannot advance within managed archive name range");
        }
        return latest.getCreatedAt() + 1L;
    }

    private static BackupVersion latest(List<BackupVersion> versions) {
        BackupVersion latest = null;
        for (BackupVersion version : versions) {
            if (latest == null || version.getCreatedAt() >= latest.getCreatedAt()) {
                latest = version;
            }
        }
        return latest;
    }

    private static long sumIncludedBytes(SnapshotManifest manifest, List<String> paths)
            throws BackupException {
        long total = 0L;
        for (String path : paths) {
            FileFingerprint fingerprint = manifest.get(path);
            if (fingerprint == null || Long.MAX_VALUE - total < fingerprint.getSize()) {
                throw new BackupException("worldbackup.error.disk_space");
            }
            total += fingerprint.getSize();
        }
        return total;
    }

    private static long sumManifestBytes(SnapshotManifest manifest) throws BackupException {
        return sumIncludedBytes(manifest, new ArrayList<>(manifest.getFiles().keySet()));
    }

    private static Path resolveArchive(Path repository, String archiveName)
            throws BackupException {
        String safe = PortablePaths.requireSafeBasename(archiveName);
        Path archive = repository.resolve(safe).normalize();
        if (!archive.getParent().equals(repository)) {
            throw new BackupException("worldbackup.error.path_escape", archiveName);
        }
        return archive;
    }

    private static void cleanupUnreferencedArchives(Path repository,
                                                    BackupCatalog retainedCatalog,
                                                    List<String> archiveNames) {
        Set<String> retainedArchives = new HashSet<>();
        for (BackupVersion version : retainedCatalog.getVersions()) {
            retainedArchives.add(version.getArchiveName());
        }
        for (String archiveName : archiveNames) {
            if (!retainedArchives.contains(archiveName)) {
                deleteManagedArchive(repository, archiveName);
            }
        }
        List<String> unreferenced = new ArrayList<>();
        try (Stream<Path> entries = Files.list(repository)) {
            entries.map(entry -> entry.getFileName().toString())
                .filter(name -> !retainedArchives.contains(name))
                .filter(name -> MANAGED_ARCHIVE_NAME.matcher(name).matches())
                .forEach(unreferenced::add);
        } catch (IOException | RuntimeException ignored) {
            // Cleanup is best effort after the new archive and catalog are committed.
        }
        for (String archiveName : unreferenced) {
            deleteManagedArchive(repository, archiveName);
        }
    }

    private static void deleteManagedArchive(Path repository, String archiveName) {
        try {
            if (!MANAGED_ARCHIVE_NAME.matcher(archiveName).matches()) {
                return;
            }
            Path archive = resolveArchive(repository, archiveName);
            if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(archive)) {
                Files.deleteIfExists(archive);
            }
        } catch (BackupException | IOException | RuntimeException ignored) {
            // A later successful backup retries unreferenced managed archives.
        }
    }

    private static void cleanupNewArchive(Path archive, BackupException primary) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static void checkCancellation(BackupCancellation cancellation)
            throws BackupException {
        try {
            cancellation.check();
        } catch (InterruptedIOException failure) {
            throw new BackupException("worldbackup.error.cancelled", failure);
        }
    }

    private static Path requireWorldRoot(Path worldRoot) throws BackupException {
        try {
            Path absolute = worldRoot.toAbsolutePath().normalize();
            Path real = absolute.toRealPath();
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
                    || !absolute.equals(real)) {
                throw new BackupException("worldbackup.error.path_escape", worldRoot);
            }
            return real;
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.scan", failure);
        }
    }

    private static Path requireRepositoryRoot(Path repoRoot, Path world)
            throws BackupException {
        try {
            Path absolute = repoRoot.toAbsolutePath().normalize();
            if (absolute.startsWith(world) || world.startsWith(absolute)) {
                throw new BackupException("worldbackup.error.path_escape", repoRoot);
            }
            Path current = absolute.getRoot();
            if (current == null) {
                throw new BackupException("worldbackup.error.path_escape", repoRoot);
            }
            verifyRepositoryDirectory(current, repoRoot);
            for (Path component : absolute) {
                current = current.resolve(component);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    verifyRepositoryDirectory(current, repoRoot);
                } else {
                    Files.createDirectory(current);
                    verifyRepositoryDirectory(current, repoRoot);
                }
            }
            Path real = current.toRealPath();
            if (!absolute.equals(real)
                    || real.startsWith(world) || world.startsWith(real)) {
                throw new BackupException("worldbackup.error.path_escape", repoRoot);
            }
            return real;
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.archive_write", failure);
        }
    }

    private static void verifyRepositoryDirectory(Path directory, Path requestedRoot)
            throws IOException, BackupException {
        Path lexical = directory.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(lexical,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || Files.isSymbolicLink(lexical)
                || !lexical.equals(lexical.toRealPath())) {
            throw new BackupException("worldbackup.error.path_escape", requestedRoot);
        }
    }

    private static void validateIdentity(BackupCatalog catalog, String worldId)
            throws BackupException {
        if (!worldId.equals(catalog.getWorldId())) {
            throw new BackupException("worldbackup.error.catalog_mismatch");
        }
    }

    private static void validateArguments(Path worldRoot, Path repoRoot, String worldId,
                                          String displayName, BackupMode mode,
                                          BackupTrigger trigger, int maxVisibleVersions,
                                          int incrementsPerBase, long startedAtMillis,
                                          BackupCancellation cancellation) {
        Objects.requireNonNull(worldRoot, "worldRoot");
        Objects.requireNonNull(repoRoot, "repoRoot");
        requireText(worldId, "worldId");
        requireText(displayName, "displayName");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(cancellation, "cancellation");
        if (maxVisibleVersions < 1) {
            throw new IllegalArgumentException("maxVisibleVersions must be at least 1");
        }
        if (incrementsPerBase < 1) {
            throw new IllegalArgumentException("incrementsPerBase must be at least 1");
        }
        if (startedAtMillis < 0L) {
            throw new IllegalArgumentException("startedAtMillis must not be negative");
        }
        requireManagedTimestamp(startedAtMillis, "startedAtMillis");
    }

    private static void requireManagedTimestamp(long value, String name) {
        if (value < 0L || value > MAX_MANAGED_TIMESTAMP_MILLIS) {
            throw new IllegalArgumentException(name
                + " must be within the managed archive timestamp range");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    interface SpaceProbe {
        long usableSpace(Path repository) throws IOException;
    }

    interface Clock {
        long currentTimeMillis();
    }

    interface IdSource {
        String nextEightHex();
    }

    private static final class ParentState {
        private final BackupVersion version;
        private final SnapshotManifest manifest;
        private final String baseId;

        private ParentState(BackupVersion version, SnapshotManifest manifest, String baseId) {
            this.version = version;
            this.manifest = manifest;
            this.baseId = baseId;
        }

        private static ParentState none() {
            return new ParentState(null, null, null);
        }
    }

    private static final class ChainState {
        private final boolean compatible;
        private final int deltaCount;
        private final String baseId;

        private ChainState(boolean compatible, int deltaCount, String baseId) {
            this.compatible = compatible;
            this.deltaCount = deltaCount;
            this.baseId = baseId;
        }

        private static ChainState incompatible() {
            return new ChainState(false, 0, null);
        }
    }

    private static final class Creation {
        private final BackupMode effectiveMode;
        private final String archiveSuffix;
        private final String parentId;
        private final String baseId;
        private final List<String> includedPaths;
        private final List<String> changedPaths;
        private final List<String> deletedPaths;

        private Creation(BackupMode effectiveMode, String archiveSuffix, String parentId,
                         String baseId, List<String> includedPaths,
                         List<String> changedPaths, List<String> deletedPaths) {
            this.effectiveMode = effectiveMode;
            this.archiveSuffix = archiveSuffix;
            this.parentId = parentId;
            this.baseId = baseId;
            this.includedPaths = includedPaths;
            this.changedPaths = changedPaths;
            this.deletedPaths = deletedPaths;
        }

        private static Creation independent(BackupMode mode, String suffix,
                                            SnapshotManifest snapshot) {
            List<String> included = new ArrayList<>(snapshot.getFiles().keySet());
            Collections.sort(included);
            return new Creation(mode, suffix, null, null, included,
                Collections.emptyList(), Collections.emptyList());
        }
    }
}
