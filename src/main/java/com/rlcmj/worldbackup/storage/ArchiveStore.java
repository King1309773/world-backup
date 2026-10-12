package com.rlcmj.worldbackup.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.PortablePaths;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ArchiveStore {
    private static final int BUFFER_SIZE = 8192;
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String DATA_PREFIX = "data/";

    interface PublishOperations {
        void move(Path source, Path target, CopyOption... options) throws IOException;

        OutputStream newOutputStream(Path path, OpenOption... options) throws IOException;

        void beforeFallbackMove(Path source) throws IOException;
    }

    private static final PublishOperations NIO_PUBLISH = new PublishOperations() {
        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            Files.move(source, target, options);
        }

        @Override
        public OutputStream newOutputStream(Path path, OpenOption... options)
                throws IOException {
            return Files.newOutputStream(path, options);
        }

        @Override
        public void beforeFallbackMove(Path source) {
            // Test seam. Production publishing has no work between final verification and move.
        }
    };

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final PublishOperations publishOperations;

    public ArchiveStore() {
        this(NIO_PUBLISH);
    }

    ArchiveStore(PublishOperations publishOperations) {
        this.publishOperations = Objects.requireNonNull(publishOperations,
            "publishOperations");
    }

    public Path write(Path repo, String archiveName, Path worldRoot,
                      SnapshotManifest manifest, List<String> includedPaths,
                      List<String> deletedPaths, BackupCancellation cancellation)
            throws BackupException {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(worldRoot, "worldRoot");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(cancellation, "cancellation");

        Path staging = null;
        try {
            cancellation.check();
            String safeArchiveName = PortablePaths.requireSafeBasename(archiveName);
            SnapshotManifest safeManifest = validatedManifest(manifest);
            List<String> included = validatedPaths(includedPaths, "includedPaths");
            List<String> deleted = validatedPaths(deletedPaths, "deletedPaths");
            validateRelationships(safeManifest, included, deleted);

            Path repository = repo.toAbsolutePath().normalize();
            Files.createDirectories(repository);
            Path resolvedRepository = repository.toRealPath();
            Path target = resolvedRepository.resolve(safeArchiveName).normalize();
            requireContained(resolvedRepository, target, safeArchiveName);
            Path resolvedRoot = requireUnsymbolicDirectory(worldRoot, "worldRoot");
            byte[] encodedManifest = encodeManifest(safeManifest, included, deleted);

            staging = Files.createTempFile(resolvedRepository,
                "." + safeArchiveName + "-", ".building");
            writeZip(staging, resolvedRoot, safeManifest, included,
                encodedManifest, cancellation);
            validateZip(staging, cancellation);
            ArchiveBaseline verifiedBaseline = ArchiveBaseline.capture(staging, cancellation);

            Path verifiedArchive = staging;
            publishVerified(verifiedArchive, target, verifiedBaseline, cancellation);
            return target;
        } catch (InterruptedIOException failure) {
            throw new BackupException("worldbackup.error.cancelled", failure);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new BackupException("worldbackup.error.archive_write", failure);
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.archive_write", failure);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // The primary failure is more useful; a later cleanup can remove this file.
                }
            }
        }
    }

    public SnapshotManifest readManifest(Path archive) throws BackupException {
        Objects.requireNonNull(archive, "archive");
        try (ValidatedArchive validated = openValidatedArchive(archive, null)) {
            return validated.metadata.snapshot;
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw corruptArchive(failure);
        }
    }

    public void extract(Path archive, Path target) throws BackupException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(target, "target");
        try (ValidatedArchive validated = openValidatedArchive(archive, null)) {
            Path targetRoot = createUnsymbolicTargetRoot(target);
            for (DataEntry dataEntry : validated.dataEntries) {
                extractEntry(validated.zip, dataEntry, targetRoot,
                    validated.metadata.snapshot.get(dataEntry.relative));
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new BackupException("worldbackup.error.archive_extract", failure);
        }
    }

    private void writeZip(Path staging, Path worldRoot, SnapshotManifest manifest,
                          List<String> included, byte[] encodedManifest,
                          BackupCancellation cancellation)
            throws IOException, BackupException {
        try (OutputStream file = Files.newOutputStream(staging, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            for (String relative : included) {
                cancellation.check();
                SourceState state = SourceState.capture(worldRoot, relative);
                ZipEntry entry = deterministicEntry(DATA_PREFIX + relative);
                zip.putNextEntry(entry);
                MessageDigest digest = sha256();
                long copied = 0L;
                try (InputStream input = Files.newInputStream(state.path,
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        cancellation.check();
                        zip.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        copied += count;
                    }
                }
                zip.closeEntry();
                state.verify();
                FileFingerprint expected = manifest.get(relative);
                if (copied != expected.getSize()
                        || !lowercaseHex(digest.digest()).equals(expected.getSha256())
                        || state.modifiedTimeMillis != expected.getModifiedTimeMillis()) {
                    throw new IOException("File no longer matches snapshot: " + relative);
                }
            }

            cancellation.check();
            zip.putNextEntry(deterministicEntry(MANIFEST_NAME));
            zip.write(encodedManifest);
            zip.closeEntry();
        }
    }

    private byte[] encodeManifest(SnapshotManifest snapshot, List<String> included,
                                  List<String> deleted) {
        JsonObject document = new JsonObject();
        document.add("snapshot", gson.toJsonTree(snapshot));
        document.add("includedPaths", strings(included));
        document.add("deletedPaths", strings(deleted));
        return gson.toJson(document).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonArray strings(List<String> paths) {
        JsonArray values = new JsonArray();
        for (String path : paths) {
            values.add(new JsonPrimitive(path));
        }
        return values;
    }

    private static ZipEntry deterministicEntry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        return entry;
    }

    private static PayloadFingerprint copy(Path source, OutputStream output,
                                           BackupCancellation cancellation)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        MessageDigest digest = sha256();
        long size = 0L;
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancellation.check();
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                size += count;
            }
            cancellation.check();
        }
        return new PayloadFingerprint(size, lowercaseHex(digest.digest()));
    }

    private void publishVerified(Path verifiedArchive, Path target,
                                 ArchiveBaseline verifiedBaseline,
                                 BackupCancellation cancellation)
            throws IOException {
        Path rollback = null;
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ArchiveBaseline targetBaseline = ArchiveBaseline.capture(target,
                    cancellation);
                rollback = copyToUniqueFile(target, targetBaseline, parent,
                    ".atomic-rollback", cancellation).path;
                targetBaseline.verify(cancellation);
            }
            final Path savedTarget = rollback;
            cancellation.publish(
                () -> publishOperations.move(verifiedArchive, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                () -> rollbackAtomicPublish(savedTarget, target));
            cleanupPublishTemp(rollback, null);
            rollback = null;
        } catch (AtomicMoveNotSupportedException unsupported) {
            cleanupPublishTemp(rollback, unsupported);
            rollback = null;
            publishVerifiedWithoutAtomicMove(verifiedArchive, target, verifiedBaseline,
                cancellation);
        }
    }

    private void rollbackAtomicPublish(Path rollback, Path target) throws IOException {
        if (rollback != null) {
            try {
                publishOperations.move(rollback, target,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new IOException("Failed to restore archive; recovery copy preserved at "
                    + rollback.toAbsolutePath().normalize(), failure);
            }
        } else {
            Files.deleteIfExists(target);
        }
    }

    private void publishVerifiedWithoutAtomicMove(Path verifiedArchive, Path target,
                                                  ArchiveBaseline verifiedBaseline,
                                                  BackupCancellation cancellation)
            throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        Path publishTemp = null;
        Path rollback = null;
        ArchiveBaseline rollbackBaseline = null;
        boolean moveAttempted = false;
        try {
            verifiedBaseline.verify(cancellation);
            PreparedFile publish = copyToUniqueFile(verifiedArchive, verifiedBaseline,
                parent, ".publish", cancellation);
            publishTemp = publish.path;
            validateZip(publishTemp, cancellation);
            publish.baseline.verifyMatches(verifiedBaseline.fingerprint, cancellation);

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                ArchiveBaseline targetBaseline = ArchiveBaseline.capture(target,
                    cancellation);
                PreparedFile saved = copyToUniqueFile(target, targetBaseline, parent,
                    ".rollback", cancellation);
                rollback = saved.path;
                rollbackBaseline = saved.baseline;
                targetBaseline.verify(cancellation);
            }

            cancellation.check();
            publish.baseline.verifyMatches(verifiedBaseline.fingerprint, cancellation);
            publishOperations.beforeFallbackMove(publishTemp);
            publish.baseline.verifyMatches(verifiedBaseline.fingerprint, cancellation);
            final Path readyToPublish = publishTemp;
            moveAttempted = true;
            cancellation.publish(() -> publishOperations.move(readyToPublish, target,
                StandardCopyOption.REPLACE_EXISTING));
            publishTemp = null;
            cancellation.check();

            if (rollback != null) {
                Files.delete(rollback);
                rollback = null;
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (moveAttempted) {
                try {
                    if (rollback != null) {
                        rollbackBaseline.verify(null);
                        publishOperations.move(rollback, target,
                            StandardCopyOption.REPLACE_EXISTING);
                        rollback = null;
                    } else if (publishTemp == null
                            || !Files.exists(publishTemp, LinkOption.NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(target);
                    }
                } catch (IOException restoreFailure) {
                    Path recoveryPath = rollback == null ? target : rollback;
                    IOException recovery = new IOException(failure.getMessage()
                        + "; publish recovery requires attention at "
                        + recoveryPath.toAbsolutePath().normalize(), failure);
                    recovery.addSuppressed(restoreFailure);
                    cleanupPublishTemp(publishTemp, recovery);
                    throw recovery;
                }
            }
            cleanupPublishTemp(publishTemp, failure);
            cleanupPublishTemp(rollback, failure);
            throw failure;
        }
        cleanupPublishTemp(publishTemp, null);
        cleanupPublishTemp(rollback, null);
    }

    private PreparedFile copyToUniqueFile(Path source, ArchiveBaseline sourceBaseline,
                                          Path parent, String suffix,
                                          BackupCancellation cancellation) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Path candidate = parent.resolve(".wba-" + UUID.randomUUID() + suffix);
            OutputStream output;
            try {
                output = publishOperations.newOutputStream(candidate,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            } catch (FileAlreadyExistsException occupied) {
                continue;
            }

            try {
                sourceBaseline.verify(cancellation);
                PayloadFingerprint copied;
                try (OutputStream opened = output) {
                    copied = copy(source, opened, cancellation);
                    opened.flush();
                }
                sourceBaseline.verify(cancellation);
                if (!sameFingerprint(copied, sourceBaseline.fingerprint)) {
                    throw new IOException("Publish source changed while copying: " + source);
                }
                ArchiveBaseline candidateBaseline = ArchiveBaseline.capture(candidate,
                    cancellation);
                if (!sameFingerprint(candidateBaseline.fingerprint,
                        sourceBaseline.fingerprint)) {
                    throw new IOException("Publish temporary file changed after writing: "
                        + candidate);
                }
                return new PreparedFile(candidate, candidateBaseline);
            } catch (IOException | RuntimeException | Error failure) {
                cleanupPublishTemp(candidate, failure);
                throw failure;
            }
        }
        throw new IOException("Unable to allocate a unique publish temporary file in "
            + parent);
    }

    private static PayloadFingerprint fingerprintFile(Path file,
                                                      BackupCancellation cancellation)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            return fingerprint(input, buffer, cancellation);
        }
    }

    private static boolean sameFingerprint(PayloadFingerprint first,
                                           PayloadFingerprint second) {
        return first.size == second.size && first.sha256.equals(second.sha256);
    }

    private static void cleanupPublishTemp(Path path, Throwable failure) throws IOException {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            if (failure == null) {
                throw cleanupFailure;
            }
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void validateZip(Path archive, BackupCancellation cancellation)
            throws IOException {
        try (ValidatedArchive ignored = openValidatedArchive(archive, cancellation)) {
            // Opening performs a complete central-directory, CRC, and manifest read-back.
        } catch (BackupException failure) {
            throw new IOException("Written archive failed read-back validation", failure);
        }
    }

    private static ValidatedArchive openValidatedArchive(Path archive,
                                                         BackupCancellation cancellation)
            throws IOException, BackupException {
        requireUnsymbolicRegularFile(archive, "archive");
        ZipFile zip = null;
        Set<String> names = new HashSet<>();
        Set<String> portableNames = new HashSet<>();
        byte[] manifest = null;
        byte[] buffer = new byte[BUFFER_SIZE];
        Map<String, PayloadFingerprint> payloads = new TreeMap<>();
        List<DataEntry> dataEntries = new ArrayList<>();
        try {
            zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (cancellation != null) {
                    cancellation.check();
                }
                ZipEntry entry = entries.nextElement();
                String name = validateEntryName(entry);
                if (!names.add(name)
                        || !portableNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw corruptArchive(null);
                }
                CRC32 crc = new CRC32();
                long uncompressedSize;
                try (InputStream input = new CheckedInputStream(
                        zip.getInputStream(entry), crc)) {
                    if (MANIFEST_NAME.equals(name)) {
                        if (manifest != null) {
                            throw corruptArchive(null);
                        }
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        uncompressedSize = drain(input, buffer, output, cancellation);
                        manifest = output.toByteArray();
                    } else if (name.startsWith(DATA_PREFIX)) {
                        String relative = name.substring(DATA_PREFIX.length());
                        if (relative.isEmpty()) {
                            throw corruptArchive(null);
                        }
                        PayloadFingerprint payload = fingerprint(input, buffer, cancellation);
                        uncompressedSize = payload.size;
                        payloads.put(relative, payload);
                        dataEntries.add(new DataEntry(entry, relative));
                    } else {
                        uncompressedSize = drain(input, buffer, null, cancellation);
                    }
                }
                verifyEntryIntegrity(entry, uncompressedSize, crc.getValue());
            }
            if (manifest == null) {
                throw corruptArchive(null);
            }
            ArchiveManifest metadata = parseManifest(manifest);
            if (!payloads.keySet().equals(new TreeSet<>(metadata.included))) {
                throw corruptArchive(null);
            }
            for (String relative : metadata.included) {
                FileFingerprint expected = metadata.snapshot.get(relative);
                PayloadFingerprint actual = payloads.get(relative);
                if (expected == null || actual.size != expected.getSize()
                        || !actual.sha256.equals(expected.getSha256())) {
                    throw corruptArchive(null);
                }
            }
            if (cancellation != null) {
                cancellation.check();
            }
            return new ValidatedArchive(zip, metadata,
                Collections.unmodifiableList(dataEntries));
        } catch (InterruptedIOException failure) {
            closeOnFailure(zip, failure);
            throw failure;
        } catch (BackupException failure) {
            closeOnFailure(zip, failure);
            throw failure;
        } catch (IOException | RuntimeException failure) {
            closeOnFailure(zip, failure);
            throw corruptArchive(failure);
        }
    }

    private static PayloadFingerprint fingerprint(InputStream input, byte[] buffer,
                                                  BackupCancellation cancellation)
            throws IOException {
        MessageDigest digest = sha256();
        long size = 0L;
        while (true) {
            if (cancellation != null) {
                cancellation.check();
            }
            int count = input.read(buffer);
            if (count == -1) {
                break;
            }
            digest.update(buffer, 0, count);
            size += count;
        }
        return new PayloadFingerprint(size, lowercaseHex(digest.digest()));
    }

    private static void closeOnFailure(ZipFile zip, Throwable failure) {
        if (zip == null) {
            return;
        }
        try {
            zip.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static long drain(InputStream input, byte[] buffer, OutputStream copy,
                              BackupCancellation cancellation)
            throws IOException {
        long size = 0L;
        while (true) {
            if (cancellation != null) {
                cancellation.check();
            }
            int count = input.read(buffer);
            if (count == -1) {
                break;
            }
            if (copy != null) {
                copy.write(buffer, 0, count);
            }
            size += count;
        }
        return size;
    }

    private static void verifyEntryIntegrity(ZipEntry entry, long size, long crc)
            throws BackupException {
        if (entry.getSize() < 0L || entry.getSize() != size
                || entry.getCrc() < 0L || entry.getCrc() != crc) {
            throw corruptArchive(null);
        }
    }

    private static void extractEntry(ZipFile zip, DataEntry dataEntry, Path targetRoot,
                                     FileFingerprint expected)
            throws IOException, BackupException {
        Path destination = prepareDestination(targetRoot, dataEntry.relative);
        // Keep payload writes directly below the verified root. A nested parent that is
        // concurrently replaced by a reparse point cannot redirect the staging write.
        Path staging = Files.createTempFile(targetRoot, ".wba-", ".extracting");
        try {
            requireContained(targetRoot, staging, dataEntry.relative);
            MessageDigest digest = sha256();
            long size = 0L;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = zip.getInputStream(dataEntry.entry);
                 OutputStream output = Files.newOutputStream(staging,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                     LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    size += count;
                }
            }
            if (expected == null || size != expected.getSize()
                    || !lowercaseHex(digest.digest()).equals(expected.getSha256())) {
                throw corruptArchive(null);
            }
            verifyExtractedPath(targetRoot, staging, dataEntry.relative);
            prepareDestination(targetRoot, dataEntry.relative);
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            staging = null;
            verifyExtractedPath(targetRoot, destination, dataEntry.relative);
        } finally {
            if (staging != null) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private static ArchiveManifest parseManifest(byte[] encoded) throws BackupException {
        try {
            String text = new String(encoded, StandardCharsets.UTF_8);
            JsonElement rootElement = new JsonParser().parse(text);
            if (!rootElement.isJsonObject()) {
                throw corruptArchive(null);
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonObject snapshot = requiredObject(root, "snapshot");
            JsonObject files = requiredObject(snapshot, "files");
            Map<String, FileFingerprint> parsedFiles = new TreeMap<>();
            Set<String> portablePaths = new HashSet<>();
            for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
                String path = PortablePaths.normalizeRelativePath(entry.getKey());
                if (!portablePaths.add(path.toLowerCase(Locale.ROOT))
                        || !entry.getValue().isJsonObject()) {
                    throw corruptArchive(null);
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                long size = requiredLong(value, "size");
                long modified = requiredLong(value, "modifiedTimeMillis");
                String sha = requiredString(value, "sha256");
                FileFingerprint fingerprint = new FileFingerprint(size, modified, sha);
                requireValidFingerprint(fingerprint, path);
                parsedFiles.put(path, fingerprint);
            }
            SnapshotManifest parsedSnapshot = new SnapshotManifest(parsedFiles);
            List<String> included = requiredPaths(root, "includedPaths");
            List<String> deleted = requiredPaths(root, "deletedPaths");
            validateRelationships(parsedSnapshot, included, deleted);
            return new ArchiveManifest(parsedSnapshot, included, deleted);
        } catch (BackupException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw corruptArchive(failure);
        }
    }

    private static JsonObject requiredObject(JsonObject parent, String name)
            throws BackupException {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonObject()) {
            throw corruptArchive(null);
        }
        return value.getAsJsonObject();
    }

    private static long requiredLong(JsonObject parent, String name) throws BackupException {
        String value = requiredString(parent, name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw corruptArchive(failure);
        }
    }

    private static String requiredString(JsonObject parent, String name) throws BackupException {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString() && name.equals("sha256")) {
            throw corruptArchive(null);
        }
        return value.getAsString();
    }

    private static List<String> requiredPaths(JsonObject parent, String name)
            throws BackupException {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonArray()) {
            throw corruptArchive(null);
        }
        List<String> raw = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw corruptArchive(null);
            }
            raw.add(item.getAsString());
        }
        try {
            return validatedPaths(raw, name);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw corruptArchive(failure);
        }
    }

    private static SnapshotManifest validatedManifest(SnapshotManifest manifest) {
        Map<String, FileFingerprint> sorted = new TreeMap<>();
        Set<String> normalized = new HashSet<>();
        for (Map.Entry<String, FileFingerprint> entry : manifest.getFiles().entrySet()) {
            String path = PortablePaths.normalizeRelativePath(entry.getKey());
            if (!normalized.add(path.toLowerCase(Locale.ROOT))
                    || entry.getValue() == null) {
                throw new IllegalArgumentException("Duplicate or null manifest path: " + path);
            }
            requireValidFingerprint(entry.getValue(), path);
            sorted.put(path, entry.getValue());
        }
        return new SnapshotManifest(sorted);
    }

    private static List<String> validatedPaths(List<String> paths, String name) {
        Objects.requireNonNull(paths, name);
        Set<String> normalized = new TreeSet<>();
        Set<String> portableKeys = new HashSet<>();
        for (String path : paths) {
            String portable = PortablePaths.normalizeRelativePath(path);
            if (!normalized.add(portable)
                    || !portableKeys.add(portable.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate " + name + " path: " + portable);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    private static void validateRelationships(SnapshotManifest manifest,
                                              List<String> included,
                                              List<String> deleted) {
        Set<String> manifestKeys = new HashSet<>();
        for (String path : manifest.getFiles().keySet()) {
            manifestKeys.add(path.toLowerCase(Locale.ROOT));
        }
        Set<String> includedKeys = new HashSet<>();
        for (String path : included) {
            if (!manifest.getFiles().containsKey(path)) {
                throw new IllegalArgumentException("Included path is absent from snapshot: "
                    + path);
            }
            includedKeys.add(path.toLowerCase(Locale.ROOT));
        }
        for (String path : deleted) {
            String portableKey = path.toLowerCase(Locale.ROOT);
            if (includedKeys.contains(portableKey) || manifestKeys.contains(portableKey)) {
                throw new IllegalArgumentException("Deleted path is present in snapshot: "
                    + path);
            }
        }
    }

    private static void requireValidFingerprint(FileFingerprint fingerprint, String path) {
        String sha256 = fingerprint.getSha256();
        if (sha256 == null || sha256.length() != 64) {
            throw new IllegalArgumentException("Invalid SHA-256 for path: " + path);
        }
        for (int index = 0; index < sha256.length(); index++) {
            char value = sha256.charAt(index);
            if (value < '0' || value > '9' && value < 'a' || value > 'f') {
                throw new IllegalArgumentException("Invalid SHA-256 for path: " + path);
            }
        }
    }

    private static String validateEntryName(ZipEntry entry) throws BackupException {
        if (entry.isDirectory()) {
            throw corruptArchive(null);
        }
        String original = entry.getName();
        try {
            String normalized = PortablePaths.normalizeRelativePath(original);
            if (original.startsWith("/") || original.matches("^[A-Za-z]:.*")) {
                throw corruptArchive(null);
            }
            return normalized;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw corruptArchive(failure);
        }
    }

    private static Path createUnsymbolicTargetRoot(Path target) throws IOException,
            BackupException {
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        return requireUnsymbolicDirectory(absolute, "target");
    }

    private static Path prepareDestination(Path targetRoot, String relative)
            throws IOException, BackupException {
        String portable = PortablePaths.normalizeRelativePath(relative);
        Path destination = targetRoot.resolve(portable).normalize();
        requireContained(targetRoot, destination, portable);
        Path parent = destination.getParent();
        Path current = targetRoot;
        Path relativeParent = targetRoot.relativize(parent);
        for (Path segment : relativeParent) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireUnsymbolicDirectory(current, portable);
            } else {
                Files.createDirectory(current);
                requireUnsymbolicDirectory(current, portable);
            }
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(destination,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw corruptArchive(null);
            }
            Path real = destination.toRealPath(LinkOption.NOFOLLOW_LINKS);
            requireContained(targetRoot, real, portable);
        }
        return destination;
    }

    private static void verifyExtractedPath(Path targetRoot, Path destination,
                                            String relative)
            throws IOException, BackupException {
        BasicFileAttributes attributes = Files.readAttributes(destination,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw corruptArchive(null);
        }
        Path real = destination.toRealPath();
        requireContained(targetRoot, real, relative);
        if (!real.equals(destination.toAbsolutePath().normalize())) {
            throw corruptArchive(null);
        }
        Path current = destination.getParent();
        while (!current.equals(targetRoot)) {
            requireUnsymbolicDirectory(current, relative);
            current = current.getParent();
        }
    }

    private static Path requireUnsymbolicDirectory(Path directory, String description)
            throws IOException, BackupException {
        Path absolute = directory.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(absolute,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new BackupException("worldbackup.error.path_escape", description);
        }
        Path real = absolute.toRealPath();
        if (!real.equals(absolute)) {
            throw new BackupException("worldbackup.error.path_escape", description);
        }
        return real;
    }

    private static void requireUnsymbolicRegularFile(Path file, String description)
            throws IOException, BackupException {
        Path absolute = file.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(absolute,
            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || !absolute.toRealPath().equals(absolute)) {
            throw new BackupException("worldbackup.error.path_escape", description);
        }
    }

    private static void requireContained(Path root, Path candidate, String description)
            throws BackupException {
        if (!candidate.toAbsolutePath().normalize().startsWith(
                root.toAbsolutePath().normalize())) {
            throw new BackupException("worldbackup.error.path_escape", description);
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 is unavailable", failure);
        }
    }

    private static String lowercaseHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] characters = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            characters[index * 2] = digits[value >>> 4];
            characters[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(characters);
    }

    private static BackupException corruptArchive(Throwable cause) {
        return cause == null
            ? new BackupException("worldbackup.error.archive_corrupt")
            : new BackupException("worldbackup.error.archive_corrupt", cause);
    }

    private static final class ValidatedArchive implements AutoCloseable {
        private final ZipFile zip;
        private final ArchiveManifest metadata;
        private final List<DataEntry> dataEntries;

        private ValidatedArchive(ZipFile zip, ArchiveManifest metadata,
                                 List<DataEntry> dataEntries) {
            this.zip = zip;
            this.metadata = metadata;
            this.dataEntries = dataEntries;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    private static final class DataEntry {
        private final ZipEntry entry;
        private final String relative;

        private DataEntry(ZipEntry entry, String relative) {
            this.entry = entry;
            this.relative = relative;
        }
    }

    private static final class PayloadFingerprint {
        private final long size;
        private final String sha256;

        private PayloadFingerprint(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class PublishFileState {
        private final Path path;
        private final long size;
        private final long modifiedTimeMillis;
        private final Object fileKey;

        private PublishFileState(Path path, BasicFileAttributes attributes) {
            this.path = path;
            this.size = attributes.size();
            this.modifiedTimeMillis = attributes.lastModifiedTime().toMillis();
            this.fileKey = attributes.fileKey();
        }

        private static PublishFileState capture(Path path) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(absolute,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || !absolute.toRealPath().equals(absolute)) {
                throw new IOException("Publish path is not an unsymbolic regular file: "
                    + absolute);
            }
            return new PublishFileState(absolute, attributes);
        }

        private void verifyIdentity() throws IOException {
            BasicFileAttributes current = currentAttributes();
            if (fileKey != null && current.fileKey() != null
                    && !fileKey.equals(current.fileKey())) {
                throw new IOException("Publish path identity changed: " + path);
            }
        }

        private void verifyUnchanged() throws IOException {
            BasicFileAttributes current = currentAttributes();
            if (current.size() != size
                    || current.lastModifiedTime().toMillis() != modifiedTimeMillis
                    || fileKey != null && current.fileKey() != null
                        && !fileKey.equals(current.fileKey())) {
                throw new IOException("Existing archive changed during publish: " + path);
            }
        }

        private BasicFileAttributes currentAttributes() throws IOException {
            BasicFileAttributes current = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isRegularFile() || current.isSymbolicLink()
                    || !path.toRealPath().equals(path)) {
                throw new IOException("Publish path is no longer an unsymbolic regular file: "
                    + path);
            }
            return current;
        }
    }

    private static final class ArchiveBaseline {
        private final PublishFileState state;
        private final PayloadFingerprint fingerprint;

        private ArchiveBaseline(PublishFileState state, PayloadFingerprint fingerprint) {
            this.state = state;
            this.fingerprint = fingerprint;
        }

        private static ArchiveBaseline capture(Path path,
                                               BackupCancellation cancellation)
                throws IOException {
            PublishFileState state = PublishFileState.capture(path);
            PayloadFingerprint fingerprint = fingerprintFile(path, cancellation);
            state.verifyUnchanged();
            return new ArchiveBaseline(state, fingerprint);
        }

        private void verify(BackupCancellation cancellation) throws IOException {
            verifyMatches(fingerprint, cancellation);
        }

        private void verifyMatches(PayloadFingerprint expected,
                                   BackupCancellation cancellation) throws IOException {
            state.verifyUnchanged();
            PayloadFingerprint current = fingerprintFile(state.path, cancellation);
            state.verifyUnchanged();
            if (!sameFingerprint(current, expected)) {
                throw new IOException("Archive content changed during publish: " + state.path);
            }
        }
    }

    private static final class PreparedFile {
        private final Path path;
        private final ArchiveBaseline baseline;

        private PreparedFile(Path path, ArchiveBaseline baseline) {
            this.path = path;
            this.baseline = baseline;
        }
    }

    private static final class ArchiveManifest {
        private final SnapshotManifest snapshot;
        private final List<String> included;
        @SuppressWarnings("unused")
        private final List<String> deleted;

        private ArchiveManifest(SnapshotManifest snapshot, List<String> included,
                                List<String> deleted) {
            this.snapshot = snapshot;
            this.included = included;
            this.deleted = deleted;
        }
    }

    private static final class SourceState {
        private final Path path;
        private final Path root;
        private final String relative;
        private final long size;
        private final long modifiedTimeMillis;
        private final Object fileKey;

        private SourceState(Path path, Path root, String relative,
                            BasicFileAttributes attributes) {
            this.path = path;
            this.root = root;
            this.relative = relative;
            this.size = attributes.size();
            this.modifiedTimeMillis = attributes.lastModifiedTime().toMillis();
            this.fileKey = attributes.fileKey();
        }

        private static SourceState capture(Path root, String relative)
                throws IOException, BackupException {
            Path path = root.resolve(relative).normalize();
            requireContained(root, path, relative);
            verifyComponents(root, path, relative);
            BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("Backup source is not a regular file: " + relative);
            }
            return new SourceState(path, root, relative, attributes);
        }

        private void verify() throws IOException, BackupException {
            verifyComponents(root, path, relative);
            BasicFileAttributes current = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isRegularFile() || current.isSymbolicLink()
                    || current.size() != size
                    || current.lastModifiedTime().toMillis() != modifiedTimeMillis
                    || fileKey != null && current.fileKey() != null
                        && !fileKey.equals(current.fileKey())) {
                throw new IOException("Backup source changed while reading: " + relative);
            }
        }

        private static void verifyComponents(Path root, Path path, String relative)
                throws IOException, BackupException {
            Path current = root;
            Path withinRoot = root.relativize(path);
            int index = 0;
            for (Path segment : withinRoot) {
                current = current.resolve(segment);
                BasicFileAttributes attributes = Files.readAttributes(current,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                boolean last = ++index == withinRoot.getNameCount();
                if (attributes.isSymbolicLink()
                        || last && !attributes.isRegularFile()
                        || !last && !attributes.isDirectory()) {
                    throw new BackupException("worldbackup.error.path_escape", relative);
                }
                Path real = current.toRealPath();
                requireContained(root, real, relative);
                if (!real.equals(current.toAbsolutePath().normalize())) {
                    throw new BackupException("worldbackup.error.path_escape", relative);
                }
            }
        }
    }
}
