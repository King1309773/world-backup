package com.rlcmj.worldbackup.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.PortablePaths;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BackupRestorer {
    private static final String SESSION_LOCK = "session.lock";
    private static final DateTimeFormatter SAFETY_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final int WINDOWS_REPARSE_POINT = 0x400;

    private enum MoveState { COMPLETED, NOT_MOVED, AMBIGUOUS }

    private static final class DirectoryIdentity {
        private final Object fileKey;
        private final FileTime creationTime;
        private final FileTime modifiedTime;
        private final Map<String, FileFingerprint> files;
        private final Set<String> directories;

        private DirectoryIdentity(Object fileKey, FileTime creationTime,
                                  FileTime modifiedTime,
                                  Map<String, FileFingerprint> files,
                                  Set<String> directories) {
            this.fileKey = fileKey;
            this.creationTime = creationTime;
            this.modifiedTime = modifiedTime;
            this.files = files;
            this.directories = directories;
        }
    }

    private static final class RecoveryCandidate {
        private final Path path;
        private final Path expectedParent;
        private final DirectoryIdentity originalIdentity;

        private RecoveryCandidate(Path path, Path expectedParent,
                                  DirectoryIdentity originalIdentity) {
            this.path = path;
            this.expectedParent = expectedParent;
            this.originalIdentity = originalIdentity;
        }

        private static RecoveryCandidate restored(Path path) {
            return new RecoveryCandidate(path, path == null ? null : path.getParent(), null);
        }

        private static RecoveryCandidate original(Path path, Path expectedParent,
                                                  DirectoryIdentity identity) {
            return new RecoveryCandidate(path, expectedParent, identity);
        }
    }

    interface MoveOperations {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    interface Clock {
        long currentTimeMillis();
    }

    interface IdSource {
        String nextId();
    }

    private static final MoveOperations NIO_MOVES = new MoveOperations() {
        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            Files.move(source, target, options);
        }
    };

    private final ArchiveStore archiveStore;
    private final CatalogStore catalogStore;
    private final MoveOperations moves;
    private final Clock clock;
    private final IdSource ids;

    public BackupRestorer() {
        this(new ArchiveStore(), new CatalogStore(), NIO_MOVES, System::currentTimeMillis,
            () -> UUID.randomUUID().toString());
    }

    BackupRestorer(ArchiveStore archiveStore, CatalogStore catalogStore,
                   MoveOperations moves, Clock clock, IdSource ids) {
        this.archiveStore = Objects.requireNonNull(archiveStore, "archiveStore");
        this.catalogStore = Objects.requireNonNull(catalogStore, "catalogStore");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public Path restore(Path gameDir, Path savesDir, String worldId, Path repository,
                        String targetVersionId) throws BackupException {
        Objects.requireNonNull(gameDir, "gameDir");
        Objects.requireNonNull(savesDir, "savesDir");
        Objects.requireNonNull(repository, "repository");
        requireText(targetVersionId, "targetVersionId");

        Path temporary = null;
        boolean preserveTemporary = false;
        BackupException primaryFailure = null;
        try {
            String safeWorldId = PortablePaths.requireSafeBasename(worldId);
            Path game = requireDirectory(gameDir);
            Path saves = requireDirectory(savesDir);
            if (!saves.startsWith(game)) {
                throw pathEscape(savesDir);
            }
            Path repo = requireDirectory(repository);
            Path world = saves.resolve(safeWorldId).normalize();
            requireDirectChild(saves, world, worldId);
            validateWorldTarget(world);
            if (repo.startsWith(world) || world.startsWith(repo)) {
                throw pathEscape(repository);
            }

            validateCatalogFile(repo);
            BackupCatalog catalog = catalogStore.load(repo);
            if (!safeWorldId.equals(catalog.getWorldId())) {
                throw new BackupException("worldbackup.error.catalog_mismatch");
            }
            List<BackupVersion> chain = resolveChain(catalog, targetVersionId);
            validateArchives(repo, chain);

            Path temporaryRoot = createSafeDirectory(game, "worldbackup-tmp");
            temporary = createTemporaryDirectory(temporaryRoot, safeWorldId);

            reconstruct(repo, temporary, chain);
            SnapshotManifest targetManifest = chain.get(chain.size() - 1).getManifest();
            verifyRestoredTree(temporary, targetManifest);
            Path restored = swap(game, world, temporary, safeWorldId, targetManifest);
            temporary = null;
            return restored;
        } catch (BackupException failure) {
            preserveTemporary = "worldbackup.error.restore_rollback".equals(
                failure.getTranslationKey());
            primaryFailure = failure;
            throw failure;
        } catch (IllegalArgumentException | NullPointerException failure) {
            BackupException wrapped = new BackupException("worldbackup.error.path_escape",
                failure);
            primaryFailure = wrapped;
            throw wrapped;
        } catch (FileAlreadyExistsException failure) {
            BackupException wrapped = new BackupException("worldbackup.error.restore_swap",
                failure);
            primaryFailure = wrapped;
            throw wrapped;
        } catch (IOException failure) {
            BackupException wrapped = new BackupException("worldbackup.error.restore_swap",
                failure);
            primaryFailure = wrapped;
            throw wrapped;
        } finally {
            if (temporary != null && !preserveTemporary) {
                try {
                    deleteTree(temporary);
                } catch (IOException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
    }

    private List<BackupVersion> resolveChain(BackupCatalog catalog, String targetId)
            throws BackupException {
        Map<String, BackupVersion> byId = new HashMap<>();
        for (BackupVersion version : catalog.getVersions()) {
            if (byId.put(version.getId(), version) != null) {
                throw chainError("Duplicate version ID: " + version.getId());
            }
        }
        BackupVersion current = byId.get(targetId);
        if (current == null) {
            throw chainError("Unknown target version: " + targetId);
        }
        if (!current.isVisible()) {
            throw chainError("Target version is an internal dependency: " + targetId);
        }

        List<BackupVersion> reverse = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (true) {
            if (!visited.add(current.getId())) {
                throw chainError("Cycle at version: " + current.getId());
            }
            reverse.add(current);
            if (current.getMode() == BackupMode.FULL
                    || current.getMode() == BackupMode.OVERWRITE) {
                if (current.getParentId() != null || current.getBaseId() != null) {
                    throw chainError("Independent backup has dependencies: " + current.getId());
                }
                break;
            }
            if (current.getMode() != BackupMode.INCREMENTAL
                    || current.getParentId() == null || current.getBaseId() == null) {
                throw chainError("Invalid incremental version: " + current.getId());
            }
            BackupVersion parent = byId.get(current.getParentId());
            if (parent == null) {
                throw chainError("Missing parent: " + current.getParentId());
            }
            current = parent;
        }
        Collections.reverse(reverse);

        BackupVersion base = reverse.get(0);
        if (reverse.size() > 1 && base.getMode() != BackupMode.FULL) {
            throw chainError("Incremental chain does not start at a full backup");
        }
        for (int index = 1; index < reverse.size(); index++) {
            BackupVersion previous = reverse.get(index - 1);
            BackupVersion delta = reverse.get(index);
            if (delta.getMode() != BackupMode.INCREMENTAL
                    || !previous.getId().equals(delta.getParentId())
                    || !base.getId().equals(delta.getBaseId())) {
                throw chainError("Incompatible dependency at: " + delta.getId());
            }
        }
        return reverse;
    }

    private void validateArchives(Path repository, List<BackupVersion> chain)
            throws BackupException {
        for (int index = 0; index < chain.size(); index++) {
            BackupVersion version = chain.get(index);
            Path archive = resolveArchive(repository, version.getArchiveName());
            SnapshotManifest archived = archiveStore.readManifest(archive);
            if (!archived.equals(version.getManifest())) {
                throw chainError("Archive manifest differs from catalog: " + version.getId());
            }
            Set<String> expectedIncluded;
            Set<String> expectedDeleted;
            if (index == 0) {
                if (!version.getChangedPaths().isEmpty()
                        || !version.getDeletedPaths().isEmpty()) {
                    throw chainError("Independent backup has delta metadata: "
                        + version.getId());
                }
                expectedIncluded = new TreeSet<>(version.getManifest().getFiles().keySet());
                expectedDeleted = Collections.emptySet();
            } else {
                SnapshotDiff diff = SnapshotDiff.between(
                    chain.get(index - 1).getManifest(), version.getManifest());
                expectedIncluded = new TreeSet<>(diff.getChangedPaths());
                expectedDeleted = new TreeSet<>(diff.getDeletedPaths());
                if (!expectedIncluded.equals(new TreeSet<>(version.getChangedPaths()))
                        || !expectedDeleted.equals(new TreeSet<>(version.getDeletedPaths()))) {
                    throw chainError("Catalog delta does not describe its state transition: "
                        + version.getId());
                }
            }
            ArchiveMetadata metadata = readArchiveMetadata(archive);
            if (!expectedIncluded.equals(metadata.included)
                    || !expectedDeleted.equals(metadata.deleted)) {
                throw chainError("Archive delta metadata differs from catalog: "
                    + version.getId());
            }
        }
    }

    private static ArchiveMetadata readArchiveMetadata(Path archive) throws BackupException {
        try (ZipFile zip = new ZipFile(archive.toFile(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null || entry.isDirectory()) {
                throw new BackupException("worldbackup.error.archive_corrupt");
            }
            try (InputStream input = zip.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(input,
                     java.nio.charset.StandardCharsets.UTF_8)) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (!parsed.isJsonObject()) {
                    throw new BackupException("worldbackup.error.archive_corrupt");
                }
                JsonObject object = parsed.getAsJsonObject();
                return new ArchiveMetadata(readPaths(object, "includedPaths"),
                    readPaths(object, "deletedPaths"));
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new BackupException("worldbackup.error.archive_corrupt", failure);
        }
    }

    private static Set<String> readPaths(JsonObject object, String name)
            throws BackupException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new BackupException("worldbackup.error.archive_corrupt");
        }
        Set<String> result = new TreeSet<>();
        JsonArray paths = value.getAsJsonArray();
        for (JsonElement item : paths) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new BackupException("worldbackup.error.archive_corrupt");
            }
            String path = PortablePaths.normalizeRelativePath(item.getAsString());
            if (!result.add(path)) {
                throw new BackupException("worldbackup.error.archive_corrupt");
            }
        }
        return result;
    }

    private void reconstruct(Path repository, Path temporary, List<BackupVersion> chain)
            throws BackupException, IOException {
        for (int index = 0; index < chain.size(); index++) {
            requireSameDirectory(temporary);
            BackupVersion version = chain.get(index);
            if (index > 0) {
                for (String deleted : version.getDeletedPaths()) {
                    deleteRestoredPath(temporary, deleted);
                }
            }
            archiveStore.extract(resolveArchive(repository, version.getArchiveName()), temporary);
            requireSameDirectory(temporary);
            Files.deleteIfExists(temporary.resolve(SESSION_LOCK));
        }
    }

    private static void deleteRestoredPath(Path root, String relative) throws BackupException,
            IOException {
        String safe = PortablePaths.normalizeRelativePath(relative);
        if (SESSION_LOCK.equals(safe)) {
            requireSameDirectory(root);
            Files.deleteIfExists(root.resolve(SESSION_LOCK));
            return;
        }
        Path target = root.resolve(safe.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw pathEscape(relative);
        }
        Path current = root;
        for (Path component : root.relativize(target)) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(current,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(current)
                    || !current.toAbsolutePath().normalize().equals(current.toRealPath())) {
                throw pathEscape(relative);
            }
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw pathEscape(relative);
        }
        Files.deleteIfExists(target);
        removeEmptyParents(target.getParent(), root);
    }

    private static void removeEmptyParents(Path directory, Path root) throws IOException {
        Path current = directory;
        while (current != null && !current.equals(root)) {
            try {
                Files.delete(current);
            } catch (java.nio.file.DirectoryNotEmptyException notEmpty) {
                return;
            }
            current = current.getParent();
        }
    }

    static void verifyRestoredTree(Path restored, SnapshotManifest expected)
            throws BackupException {
        Objects.requireNonNull(restored, "restored");
        Objects.requireNonNull(expected, "expected");
        Map<String, FileFingerprint> actual = new HashMap<>();
        Set<String> actualDirectories = new HashSet<>();
        try {
            final Path root = requireDirectory(restored);
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE,
                strictVerificationVisitor(root, actual, actualDirectories));
        } catch (BackupException failure) {
            throw new BackupException("worldbackup.error.restore_verify", failure);
        } catch (IOException | RuntimeException failure) {
            throw new BackupException("worldbackup.error.restore_verify", failure);
        }
        Map<String, FileFingerprint> expectedFiles = new HashMap<>(expected.getFiles());
        expectedFiles.remove(SESSION_LOCK);
        if (!expectedFiles.keySet().equals(actual.keySet())
                || !expectedDirectories(expectedFiles.keySet()).equals(actualDirectories)) {
            throw new BackupException("worldbackup.error.restore_verify");
        }
        for (Map.Entry<String, FileFingerprint> entry : expectedFiles.entrySet()) {
            FileFingerprint observed = actual.get(entry.getKey());
            FileFingerprint wanted = entry.getValue();
            if (observed == null || observed.getSize() != wanted.getSize()
                    || !observed.getSha256().equals(wanted.getSha256())) {
                throw new BackupException("worldbackup.error.restore_verify",
                    entry.getKey());
            }
        }
    }

    private static Set<String> expectedDirectories(Set<String> files) {
        Set<String> directories = new HashSet<>();
        for (String file : files) {
            int separator = file.lastIndexOf('/');
            while (separator >= 0) {
                directories.add(file.substring(0, separator));
                separator = file.lastIndexOf('/', separator - 1);
            }
        }
        return directories;
    }

    private static java.nio.file.FileVisitor<Path> strictVerificationVisitor(
            Path root, Map<String, FileFingerprint> actual,
            Set<String> actualDirectories) {
        return new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes)
                    throws IOException {
                requireStrictNode(root, directory, attributes, true);
                if (!directory.equals(root)) {
                    actualDirectories.add(PortablePaths.normalizeRelativePath(
                        root.relativize(directory).toString()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                String relative = PortablePaths.normalizeRelativePath(
                    root.relativize(file).toString());
                BasicFileAttributes stable = requireStrictNode(root, file, attributes, false);
                if (SESSION_LOCK.equals(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                actual.put(relative, fingerprintStrict(file, stable));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                    throws IOException {
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                return FileVisitResult.CONTINUE;
            }
        };
    }

    private static BasicFileAttributes requireStrictNode(Path root, Path node,
                                                          BasicFileAttributes observed,
                                                          boolean directory)
            throws IOException {
        BasicFileAttributes current = Files.readAttributes(node, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        Path absolute = node.toAbsolutePath().normalize();
        boolean rightType = directory ? current.isDirectory() : current.isRegularFile();
        if (!rightType || current.isSymbolicLink() || current.isOther()
                || Files.isSymbolicLink(node) || isWindowsReparsePoint(node)
                || !directory && !sameFileState(observed, current)
                || !absolute.equals(absolute.toRealPath()) || !absolute.startsWith(root)) {
            throw new IOException("Unsafe restored node: " + node);
        }
        return current;
    }

    private static boolean isWindowsReparsePoint(Path node) throws IOException {
        try {
            Object flags = Files.getAttribute(node, "dos:attributes",
                LinkOption.NOFOLLOW_LINKS);
            return flags instanceof Number
                && (((Number) flags).intValue() & WINDOWS_REPARSE_POINT) != 0;
        } catch (UnsupportedOperationException unsupported) {
            return false;
        }
    }

    private static FileFingerprint fingerprintStrict(Path file, BasicFileAttributes before)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
        long size = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                size += count;
            }
        }
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (!sameFileState(before, after) || size != before.size()) {
            throw new IOException("Restored file changed during verification: " + file);
        }
        return new FileFingerprint(size, after.lastModifiedTime().toMillis(),
            lowercaseHex(digest.digest()));
    }

    private static boolean sameFileState(BasicFileAttributes first,
                                         BasicFileAttributes second) {
        if (first.isRegularFile() != second.isRegularFile()
                || first.isDirectory() != second.isDirectory()
                || first.isSymbolicLink() != second.isSymbolicLink()
                || first.isOther() != second.isOther() || first.size() != second.size()
                || !first.lastModifiedTime().equals(second.lastModifiedTime())) {
            return false;
        }
        Object firstKey = first.fileKey();
        Object secondKey = second.fileKey();
        return firstKey == null || secondKey == null || firstKey.equals(secondKey);
    }

    private static String lowercaseHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private Path swap(Path game, Path world, Path temporary, String worldId,
                      SnapshotManifest targetManifest)
            throws BackupException {
        Path safety = null;
        Path safetyRoot = null;
        DirectoryIdentity originalIdentity = null;
        boolean originalMoved = false;
        if (Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
            try {
                safetyRoot = createSafeDirectory(game, "restore-safety");
                safety = uniqueSafetyPath(safetyRoot, worldId);
                originalIdentity = captureDirectoryIdentity(world);
                moveSameVolume(world, safety);
            } catch (IOException safetyFailure) {
                if (!Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
                    throw recoveryFailure(safetyFailure, null, targetManifest,
                        RecoveryCandidate.restored(temporary),
                        RecoveryCandidate.original(safety, safetyRoot, originalIdentity));
                }
                throw new BackupException("worldbackup.error.restore_swap", safetyFailure);
            }
            originalMoved = true;
        }
        try {
            moveSameVolume(temporary, world);
            verifyRestoredTree(world, targetManifest);
            return world;
        } catch (IOException | BackupException promotionFailure) {
            if (!originalMoved) {
                if (observeMove(temporary, world) == MoveState.NOT_MOVED) {
                    throw new BackupException("worldbackup.error.restore_swap",
                        promotionFailure);
                }
                throw recoveryFailure(promotionFailure, null, targetManifest,
                    RecoveryCandidate.restored(temporary),
                    RecoveryCandidate.restored(world));
            }
            try {
                if (Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Cannot preserve promoted world because both "
                            + world + " and " + temporary + " exist");
                    }
                    moveSameVolume(world, temporary);
                }
                moveSameVolume(safety, world);
            } catch (IOException rollbackFailure) {
                throw recoveryFailure(promotionFailure, rollbackFailure, targetManifest,
                    RecoveryCandidate.restored(temporary),
                    RecoveryCandidate.original(safety, safetyRoot, originalIdentity),
                    RecoveryCandidate.restored(world));
            }
            throw new BackupException("worldbackup.error.restore_swap", promotionFailure);
        }
    }

    private static BackupException recoveryFailure(Throwable primary, Throwable secondary,
                                                    SnapshotManifest targetManifest,
                                                    RecoveryCandidate... candidates) {
        Path recoveryPath = firstVerifiedRecovery(targetManifest, candidates);
        String detail = recoveryPath == null
            ? "Restore rollback failed; no recovery path could be confirmed"
            : "Restore rollback failed; recovery remains at " + recoveryPath;
        IOException recovery = new IOException(detail, primary);
        if (secondary != null) {
            recovery.addSuppressed(secondary);
        }
        return recoveryPath == null
            ? new BackupException("worldbackup.error.restore_rollback", recovery)
            : new BackupException("worldbackup.error.restore_rollback", recovery,
                recoveryPath.toString());
    }

    private static Path firstVerifiedRecovery(SnapshotManifest targetManifest,
                                              RecoveryCandidate... candidates) {
        for (RecoveryCandidate candidate : candidates) {
            if (candidate == null || candidate.path == null
                    || !Files.exists(candidate.path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path absolute = candidate.path.toAbsolutePath().normalize();
            if (!hasTrustedParent(absolute, candidate.expectedParent)) {
                continue;
            }
            if (candidate.originalIdentity == null) {
                try {
                    verifyRestoredTree(absolute, targetManifest);
                    return absolute;
                } catch (BackupException invalidRecovery) {
                    continue;
                }
            }
            if (matchesDirectoryIdentity(absolute, candidate.originalIdentity)) {
                return absolute;
            }
        }
        return null;
    }

    private static boolean hasTrustedParent(Path candidate, Path expectedParent) {
        if (expectedParent == null) {
            return false;
        }
        try {
            Path parent = requireDirectory(expectedParent);
            return !isWindowsReparsePoint(parent) && candidate.getParent().equals(parent);
        } catch (BackupException | IOException invalidParent) {
            return false;
        }
    }

    private static DirectoryIdentity captureDirectoryIdentity(Path directory)
            throws BackupException, IOException {
        Path verified = requireDirectory(directory);
        BasicFileAttributes attributes = Files.readAttributes(verified,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (isWindowsReparsePoint(verified)) {
            throw pathEscape(directory);
        }
        Map<String, FileFingerprint> files = new HashMap<>();
        Set<String> directories = new HashSet<>();
        Files.walkFileTree(verified,
            EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
            strictVerificationVisitor(verified, files, directories));
        return new DirectoryIdentity(attributes.fileKey(), attributes.creationTime(),
            attributes.lastModifiedTime(), files, directories);
    }

    private static boolean matchesDirectoryIdentity(Path directory,
                                                    DirectoryIdentity expected) {
        try {
            Path verified = requireDirectory(directory);
            if (isWindowsReparsePoint(verified)) {
                return false;
            }
            BasicFileAttributes attributes = Files.readAttributes(verified,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object observedKey = attributes.fileKey();
            boolean sameRoot = expected.fileKey != null && observedKey != null
                ? expected.fileKey.equals(observedKey)
                : expected.creationTime.equals(attributes.creationTime())
                    && expected.modifiedTime.equals(attributes.lastModifiedTime());
            if (!sameRoot) {
                return false;
            }
            Map<String, FileFingerprint> files = new HashMap<>();
            Set<String> directories = new HashSet<>();
            Files.walkFileTree(verified,
                EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
                strictVerificationVisitor(verified, files, directories));
            return expected.directories.equals(directories)
                && sameFingerprints(expected.files, files);
        } catch (BackupException | IOException invalidDirectory) {
            return false;
        }
    }

    private static boolean sameFingerprints(Map<String, FileFingerprint> expected,
                                            Map<String, FileFingerprint> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        for (Map.Entry<String, FileFingerprint> entry : expected.entrySet()) {
            FileFingerprint observed = actual.get(entry.getKey());
            FileFingerprint wanted = entry.getValue();
            if (observed == null || observed.getSize() != wanted.getSize()
                    || !observed.getSha256().equals(wanted.getSha256())) {
                return false;
            }
        }
        return true;
    }

    private Path uniqueSafetyPath(Path safetyRoot, String worldId) throws BackupException {
        long now = clock.currentTimeMillis();
        if (now < 0L) {
            throw new BackupException("worldbackup.error.restore_swap");
        }
        String prefix = worldId + "-" + SAFETY_TIMESTAMP.format(Instant.ofEpochMilli(now));
        for (int attempt = 0; attempt < 1000; attempt++) {
            String name = attempt == 0 ? prefix : prefix + "-" + attempt;
            Path candidate = safetyRoot.resolve(name).normalize();
            requireDirectChild(safetyRoot, candidate, name);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new BackupException("worldbackup.error.restore_swap");
    }

    private Path createTemporaryDirectory(Path temporaryRoot, String worldId)
            throws BackupException, IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            String restoreId = PortablePaths.requireSafeBasename(ids.nextId());
            Path candidate = temporaryRoot.resolve(worldId + "-" + restoreId).normalize();
            requireDirectChild(temporaryRoot, candidate, restoreId);
            try {
                Files.createDirectory(candidate);
                try {
                    requireSameDirectory(temporaryRoot);
                    requireSameDirectory(candidate);
                    return candidate;
                } catch (BackupException unsafe) {
                    deleteTree(candidate);
                    throw unsafe;
                }
            } catch (FileAlreadyExistsException collision) {
                // A new UUID normally makes this impossible; deterministic tests exercise it.
            }
        }
        throw new BackupException("worldbackup.error.restore_swap");
    }

    private void moveSameVolume(Path source, Path target) throws IOException {
        validateMovePaths(source, target);
        try {
            moves.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            requireCompletedMove(source, target, null);
            return;
        } catch (IOException atomicFailure) {
            MoveState state = observeMove(source, target);
            if (state == MoveState.COMPLETED) {
                return;
            }
            if (!(atomicFailure instanceof AtomicMoveNotSupportedException)
                    || state == MoveState.AMBIGUOUS) {
                throw moveFailure(source, target, state, atomicFailure);
            }
        }

        validateMovePaths(source, target);
        try {
            moves.move(source, target);
            requireCompletedMove(source, target, null);
        } catch (IOException fallbackFailure) {
            MoveState state = observeMove(source, target);
            if (state == MoveState.COMPLETED) {
                return;
            }
            throw moveFailure(source, target, state, fallbackFailure);
        }
    }

    private static void requireCompletedMove(Path source, Path target, IOException cause)
            throws IOException {
        MoveState state = observeMove(source, target);
        if (state != MoveState.COMPLETED) {
            throw moveFailure(source, target, state, cause);
        }
    }

    private static MoveState observeMove(Path source, Path target) {
        boolean sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (!sourceExists && targetExists) {
            return MoveState.COMPLETED;
        }
        if (sourceExists && !targetExists) {
            return MoveState.NOT_MOVED;
        }
        return MoveState.AMBIGUOUS;
    }

    private static IOException moveFailure(Path source, Path target, MoveState state,
                                           IOException cause) {
        return new IOException("Move ended in " + state + " state (source=" + source
            + ", target=" + target + ")", cause);
    }

    private static void validateMovePaths(Path source, Path target) throws IOException {
        try {
            requireDirectory(source.getParent());
            requireDirectory(target.getParent());
            requireDirectory(source);
        } catch (BackupException unsafe) {
            throw new IOException("Move path failed safety validation", unsafe);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
    }

    private static Path resolveArchive(Path repository, String archiveName)
            throws BackupException {
        String safe = PortablePaths.requireSafeBasename(archiveName);
        Path archive = repository.resolve(safe).normalize();
        requireDirectChild(repository, archive, archiveName);
        try {
            BasicFileAttributes attributes = Files.readAttributes(archive,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || Files.isSymbolicLink(archive)
                    || !archive.toRealPath().getParent().equals(repository)) {
                throw pathEscape(archiveName);
            }
            return archive;
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.archive_corrupt", failure);
        }
    }

    private static void validateCatalogFile(Path repository) throws BackupException {
        Path index = repository.resolve("index.json").normalize();
        requireDirectChild(repository, index, "index.json");
        if (!Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(index,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || Files.isSymbolicLink(index)
                    || !index.toRealPath().getParent().equals(repository)) {
                throw pathEscape(index);
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException failure) {
            throw pathEscape(index, failure);
        }
    }

    private static Path requireDirectory(Path requested) throws BackupException {
        try {
            Path absolute = requested.toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(absolute,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path real = absolute.toRealPath();
            if (!attributes.isDirectory() || attributes.isSymbolicLink()
                    || Files.isSymbolicLink(absolute) || !absolute.equals(real)) {
                throw pathEscape(requested);
            }
            return real;
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException failure) {
            throw pathEscape(requested, failure);
        }
    }

    private static void requireSameDirectory(Path requested) throws BackupException {
        Path verified = requireDirectory(requested);
        if (!verified.equals(requested.toAbsolutePath().normalize())) {
            throw pathEscape(requested);
        }
    }

    private static Path createSafeDirectory(Path parent, String name)
            throws BackupException, IOException {
        String safe = PortablePaths.requireSafeBasename(name);
        Path directory = parent.resolve(safe).normalize();
        requireDirectChild(parent, directory, name);
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException exists) {
            // Validated below without following a reparse point.
        }
        BasicFileAttributes attributes = Files.readAttributes(directory,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || Files.isSymbolicLink(directory)
                || !directory.toAbsolutePath().normalize().equals(directory.toRealPath())) {
            throw pathEscape(directory);
        }
        return directory;
    }

    private static void validateWorldTarget(Path world) throws BackupException {
        if (!Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(world,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()
                    || Files.isSymbolicLink(world)
                    || !world.toAbsolutePath().normalize().equals(world.toRealPath())) {
                throw pathEscape(world);
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException failure) {
            throw pathEscape(world, failure);
        }
    }

    private static void requireDirectChild(Path parent, Path child, Object argument)
            throws BackupException {
        if (!child.getParent().equals(parent)) {
            throw pathEscape(argument);
        }
    }

    private static BackupException pathEscape(Object argument) {
        return new BackupException("worldbackup.error.path_escape", argument);
    }

    private static BackupException pathEscape(Object argument, Throwable cause) {
        return new BackupException("worldbackup.error.path_escape", cause, argument);
    }

    private static BackupException chainError(String detail) {
        return new BackupException("worldbackup.error.restore_chain",
            new IllegalStateException(detail));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final class ArchiveMetadata {
        private final Set<String> included;
        private final Set<String> deleted;

        private ArchiveMetadata(Set<String> included, Set<String> deleted) {
            this.included = included;
            this.deleted = deleted;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path absoluteRoot = root.toAbsolutePath().normalize();
        BasicFileAttributes rootAttributes = Files.readAttributes(absoluteRoot,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (rootAttributes.isSymbolicLink() || Files.isSymbolicLink(absoluteRoot)
                || !absoluteRoot.equals(absoluteRoot.toRealPath())) {
            Files.deleteIfExists(absoluteRoot);
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes)
                    throws IOException {
                Path absolute = directory.toAbsolutePath().normalize();
                if (!absolute.equals(absoluteRoot)) {
                    Path real = absolute.toRealPath();
                    if (attributes.isSymbolicLink() || Files.isSymbolicLink(absolute)
                            || !absolute.equals(real) || !real.startsWith(absoluteRoot)) {
                        Files.deleteIfExists(absolute);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
