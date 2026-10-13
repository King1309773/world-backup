package com.rlcmj.worldbackup.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.PortablePaths;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CatalogStore {
    private static final String INDEX_NAME = "index.json";
    // Bounds recursive parsing so hostile unknown fields cannot exhaust the JVM stack.
    static final int MAX_JSON_DEPTH = 128;
    static final int MAX_INDEX_CHARS = 64 * 1024 * 1024;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AtomicWriter atomicWriter;
    private final int maxIndexChars;

    public CatalogStore() {
        this((target, writer, publishGuard) ->
            AtomicFiles.write(target, writer, publishGuard), MAX_INDEX_CHARS);
    }

    CatalogStore(AtomicWriter atomicWriter) {
        this(atomicWriter, MAX_INDEX_CHARS);
    }

    CatalogStore(AtomicWriter atomicWriter, int maxIndexChars) {
        this.atomicWriter = Objects.requireNonNull(atomicWriter, "atomicWriter");
        if (maxIndexChars < 1) throw new IllegalArgumentException("maxIndexChars");
        this.maxIndexChars = maxIndexChars;
    }

    interface AtomicWriter {
        void write(Path target, AtomicFiles.IOConsumer<OutputStream> writer,
                   AtomicFiles.PublishGuard publishGuard) throws IOException;
    }

    public void save(Path repository, BackupCatalog catalog) throws BackupException {
        save(repository, catalog, new BackupCancellation());
    }

    public void save(Path repository, BackupCatalog catalog,
                     BackupCancellation cancellation) throws BackupException {
        Objects.requireNonNull(cancellation, "cancellation");
        validate(catalog);
        try {
            atomicWriter.write(repository.resolve(INDEX_NAME),
                output -> writeCatalog(output, catalog),
                new AtomicFiles.PublishGuard() {
                    @Override public void publish(AtomicFiles.IOAction publication)
                            throws IOException {
                        cancellation.publish(publication::run);
                    }

                    @Override public void publish(AtomicFiles.IOAction publication,
                                                  AtomicFiles.IOAction rollback)
                            throws IOException {
                        cancellation.publish(publication::run, rollback::run);
                    }

                    @Override public boolean supportsRollback() { return true; }
                });
        } catch (CatalogTooLargeException failure) {
            throw new BackupException("worldbackup.error.catalog_too_large", failure);
        } catch (InterruptedIOException failure) {
            throw new BackupException("worldbackup.error.cancelled", failure);
        } catch (JsonIOException failure) {
            CatalogTooLargeException tooLarge = findTooLarge(failure);
            if (tooLarge != null) {
                throw new BackupException("worldbackup.error.catalog_too_large", tooLarge);
            }
            throw new BackupException("worldbackup.error.catalog_write", failure);
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.catalog_write", failure);
        } catch (OutOfMemoryError failure) {
            throw new BackupException("worldbackup.error.catalog_too_large", failure);
        }
    }

    private void writeCatalog(OutputStream output, BackupCatalog catalog) throws IOException {
        LimitedWriter writer = new LimitedWriter(
            new OutputStreamWriter(output, StandardCharsets.UTF_8), maxIndexChars);
        try {
            gson.toJson(catalog, writer);
            writer.flush();
        } catch (JsonIOException failure) {
            CatalogTooLargeException tooLarge = findTooLarge(failure);
            if (tooLarge != null) throw tooLarge;
            throw failure;
        }
    }

    public BackupCatalog load(Path repository) throws BackupException {
        return load(repository, null);
    }

    public BackupCatalog load(Path repository, BackupCancellation cancellation)
            throws BackupException {
        Path index = repository.resolve(INDEX_NAME);
        try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            try {
                JsonElement json = readDocument(reader, cancellation);
                checkCancellation(cancellation);
                readFormatVersion(json);
                validateStructure(json.getAsJsonObject());
                checkCancellation(cancellation);
                BackupCatalog catalog = gson.fromJson(json, BackupCatalog.class);
                checkCancellation(cancellation);
                validate(catalog);
                return catalog;
            } catch (BackupException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new BackupException("worldbackup.error.catalog_corrupt", failure);
            } catch (StackOverflowError failure) {
                throw new BackupException("worldbackup.error.catalog_corrupt", failure);
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (InterruptedIOException failure) {
            throw new BackupException("worldbackup.error.cancelled", failure);
        } catch (CatalogTooLargeException failure) {
            throw new BackupException("worldbackup.error.catalog_too_large", failure);
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.catalog_read", failure);
        } catch (OutOfMemoryError failure) {
            throw new BackupException("worldbackup.error.catalog_too_large", failure);
        }
    }

    private JsonElement readDocument(Reader source, BackupCancellation cancellation)
            throws IOException {
        String rawJson = readAll(source, cancellation);
        checkCancellation(cancellation);
        validateRawJsonStrings(rawJson);
        checkCancellation(cancellation);
        JsonReader reader = new JsonReader(new StringReader(rawJson));
        reader.setLenient(false);
        try {
            JsonElement value = readValue(reader, 1);
            checkCancellation(cancellation);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new CatalogSyntaxException("Trailing JSON content");
            }
            return value;
        } catch (MalformedJsonException | EOFException | NumberFormatException
                 | IllegalStateException failure) {
            throw new CatalogSyntaxException("Invalid JSON", failure);
        }
    }

    private String readAll(Reader source, BackupCancellation cancellation)
            throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = source.read(buffer)) != -1) {
            if (cancellation != null) cancellation.check();
            if (text.length() > maxIndexChars - count) {
                throw new CatalogTooLargeException();
            }
            text.append(buffer, 0, count);
        }
        if (cancellation != null) cancellation.check();
        return text.toString();
    }

    private static void checkCancellation(BackupCancellation cancellation)
            throws InterruptedIOException {
        if (cancellation != null) cancellation.check();
    }

    private static CatalogTooLargeException findTooLarge(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CatalogTooLargeException) {
                return (CatalogTooLargeException) current;
            }
        }
        return null;
    }

    private static final class LimitedWriter extends Writer {
        private final Writer delegate;
        private final int limit;
        private int written;

        private LimitedWriter(Writer delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override public void write(char[] values, int offset, int length) throws IOException {
            if (written > limit - length) throw new CatalogTooLargeException();
            delegate.write(values, offset, length);
            written += length;
        }

        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private static final class CatalogTooLargeException extends IOException {
        private CatalogTooLargeException() { super("Catalog exceeds size limit"); }
    }

    private static void validateRawJsonStrings(String source) {
        boolean inString = false;
        boolean escaped = false;
        int unicodeDigits = 0;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (!inString) {
                if (value == '"') {
                    inString = true;
                } else if (value <= 0x1f && value != '\t' && value != '\n' && value != '\r') {
                    throw new CatalogSyntaxException("Invalid control character outside JSON string");
                }
                continue;
            }
            if (unicodeDigits > 0) {
                if (!isHexDigit(value)) {
                    throw new CatalogSyntaxException("Invalid JSON unicode escape");
                }
                unicodeDigits--;
                continue;
            }
            if (escaped) {
                escaped = false;
                switch (value) {
                    case '"':
                    case '\\':
                    case '/':
                    case 'b':
                    case 'f':
                    case 'n':
                    case 'r':
                    case 't':
                        break;
                    case 'u':
                        unicodeDigits = 4;
                        break;
                    default:
                        throw new CatalogSyntaxException("Invalid JSON string escape");
                }
                continue;
            }
            if (value <= 0x1f) {
                throw new CatalogSyntaxException("Unescaped control character in JSON string");
            }
            if (value == '\\') {
                escaped = true;
            } else if (value == '"') {
                inString = false;
            }
        }
        if (escaped || unicodeDigits > 0) {
            throw new CatalogSyntaxException("Incomplete JSON string escape");
        }
    }

    private static boolean isHexDigit(char value) {
        return value >= '0' && value <= '9'
            || value >= 'a' && value <= 'f'
            || value >= 'A' && value <= 'F';
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH) {
            throw new CatalogSyntaxException("JSON nesting exceeds " + MAX_JSON_DEPTH);
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT:
                return readObject(reader, depth);
            case BEGIN_ARRAY:
                return readArray(reader, depth);
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                return new JsonPrimitive(new EncodedJsonNumber(reader.nextString()));
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new CatalogSyntaxException("Unexpected JSON token: " + token);
        }
    }

    private static JsonObject readObject(JsonReader reader, int depth) throws IOException {
        JsonObject object = new JsonObject();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) {
                throw new CatalogSyntaxException("Duplicate JSON field: " + name);
            }
            object.add(name, readValue(reader, depth + 1));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray readArray(JsonReader reader, int depth) throws IOException {
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) {
            array.add(readValue(reader, depth + 1));
        }
        reader.endArray();
        return array;
    }

    private static final class CatalogSyntaxException extends RuntimeException {
        CatalogSyntaxException(String message) {
            super(message);
        }

        CatalogSyntaxException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class EncodedJsonNumber extends Number {
        private static final long serialVersionUID = 1L;
        private final String encoded;

        EncodedJsonNumber(String encoded) {
            this.encoded = encoded;
        }

        @Override
        public int intValue() {
            return new BigDecimal(encoded).intValue();
        }

        @Override
        public long longValue() {
            return new BigDecimal(encoded).longValue();
        }

        @Override
        public float floatValue() {
            return Float.parseFloat(encoded);
        }

        @Override
        public double doubleValue() {
            return Double.parseDouble(encoded);
        }

        @Override
        public String toString() {
            return encoded;
        }
    }

    private static void readFormatVersion(JsonElement json) throws BackupException {
        if (json == null || !json.isJsonObject()) {
            throw corrupt();
        }
        JsonElement version = required(json.getAsJsonObject(), "formatVersion");
        if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
            throw corrupt();
        }
        String encoded = version.getAsString();
        if (!encoded.matches("-?(0|[1-9][0-9]*)")) {
            throw corrupt();
        }
        BigInteger parsed = new BigInteger(encoded);
        if (!BigInteger.valueOf(BackupCatalog.FORMAT_VERSION).equals(parsed)) {
            Object argument = parsed.bitLength() < Integer.SIZE - 1
                ? parsed.intValue() : encoded;
            throw new BackupException("worldbackup.error.catalog_version", argument);
        }
    }

    private static void validateStructure(JsonObject catalog) throws BackupException {
        requiredString(catalog, "worldId");
        requiredString(catalog, "displayName");
        JsonArray versions = requiredArray(catalog, "versions");
        for (JsonElement value : versions) {
            if (!value.isJsonObject()) {
                throw corrupt();
            }
            validateVersionStructure(value.getAsJsonObject());
        }
    }

    private static void validateVersionStructure(JsonObject version) throws BackupException {
        requiredString(version, "id");
        requiredLong(version, "createdAt");
        requiredEnum(version, "trigger", BackupTrigger.class);
        requiredEnum(version, "mode", BackupMode.class);
        requiredString(version, "archiveName");
        optionalString(version, "parentId");
        optionalString(version, "baseId");
        requiredBoolean(version, "visible");
        requiredLong(version, "originalBytes");
        requiredLong(version, "archiveBytes");
        requiredLong(version, "durationMillis");
        validateStringArray(requiredArray(version, "changedPaths"));
        validateStringArray(requiredArray(version, "deletedPaths"));
        JsonElement manifestValue = required(version, "manifest");
        if (!manifestValue.isJsonObject()) {
            throw corrupt();
        }
        JsonObject files = requiredObject(manifestValue.getAsJsonObject(), "files");
        for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw corrupt();
            }
            JsonObject fingerprint = entry.getValue().getAsJsonObject();
            requiredLong(fingerprint, "size");
            requiredLong(fingerprint, "modifiedTimeMillis");
            requiredString(fingerprint, "sha256");
        }
    }

    private static void validateStringArray(JsonArray values) throws BackupException {
        for (JsonElement value : values) {
            if (!isString(value)) {
                throw corrupt();
            }
        }
    }

    private static String requiredString(JsonObject object, String name) throws BackupException {
        JsonElement value = required(object, name);
        if (!isString(value)) {
            throw corrupt();
        }
        return value.getAsString();
    }

    private static void optionalString(JsonObject object, String name) throws BackupException {
        JsonElement value = object.get(name);
        if (value != null && !value.isJsonNull() && !isString(value)) {
            throw corrupt();
        }
    }

    private static void requiredLong(JsonObject object, String name) throws BackupException {
        JsonElement value = required(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw corrupt();
        }
        String encoded = value.getAsString();
        if (!encoded.matches("-?(0|[1-9][0-9]*)")) {
            throw corrupt();
        }
        try {
            Long.parseLong(encoded);
        } catch (NumberFormatException failure) {
            throw corrupt();
        }
    }

    private static void requiredBoolean(JsonObject object, String name) throws BackupException {
        JsonElement value = required(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw corrupt();
        }
    }

    private static <T extends Enum<T>> void requiredEnum(JsonObject object, String name,
                                                          Class<T> type) throws BackupException {
        String value = requiredString(object, name);
        try {
            Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw corrupt();
        }
    }

    private static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws BackupException {
        JsonElement value = required(object, name);
        if (!value.isJsonArray()) {
            throw corrupt();
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String name) throws BackupException {
        JsonElement value = required(object, name);
        if (!value.isJsonObject()) {
            throw corrupt();
        }
        return value.getAsJsonObject();
    }

    private static JsonElement required(JsonObject object, String name) throws BackupException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw corrupt();
        }
        return value;
    }

    private static void validate(BackupCatalog catalog) throws BackupException {
        try {
            if (catalog == null || catalog.getFormatVersion() != BackupCatalog.FORMAT_VERSION
                || isBlank(catalog.getWorldId()) || isBlank(catalog.getDisplayName())) {
                throw corrupt();
            }
            for (BackupVersion version : catalog.getVersions()) {
                validate(version);
            }
        } catch (BackupException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BackupException("worldbackup.error.catalog_corrupt", failure);
        }
    }

    private static void validate(BackupVersion version) throws BackupException {
        if (version == null || isBlank(version.getId())
            || version.getTrigger() == null || version.getMode() == null
            || isBlank(version.getArchiveName()) || version.getManifest() == null
            || isBlankWhenPresent(version.getParentId()) || isBlankWhenPresent(version.getBaseId())
            || version.getCreatedAt() < 0L || version.getOriginalBytes() < 0L
            || version.getArchiveBytes() < 0L || version.getDurationMillis() < 0L) {
            throw corrupt();
        }
        PortablePaths.requireSafeBasename(version.getArchiveName());
        for (Map.Entry<String, FileFingerprint> entry : version.getManifest().getFiles().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                || isBlank(entry.getValue().getSha256()) || entry.getValue().getSize() < 0L
                || !entry.getKey().equals(SnapshotManifest.normalizePath(entry.getKey()))) {
                throw corrupt();
            }
        }
        for (String path : version.getChangedPaths()) {
            if (!path.equals(SnapshotManifest.normalizePath(path))) {
                throw corrupt();
            }
        }
        for (String path : version.getDeletedPaths()) {
            if (!path.equals(SnapshotManifest.normalizePath(path))) {
                throw corrupt();
            }
        }
    }

    private static BackupException corrupt() {
        return new BackupException("worldbackup.error.catalog_corrupt");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isBlankWhenPresent(String value) {
        return value != null && value.trim().isEmpty();
    }
}
