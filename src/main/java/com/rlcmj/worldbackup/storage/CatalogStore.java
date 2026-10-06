package com.rlcmj.worldbackup.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class CatalogStore {
    private static final String INDEX_NAME = "index.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void save(Path repository, BackupCatalog catalog) throws BackupException {
        validate(catalog);
        try {
            AtomicFiles.write(repository.resolve(INDEX_NAME), output -> {
                OutputStreamWriter text = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                gson.toJson(catalog, text);
                text.flush();
            });
        } catch (IOException failure) {
            throw new BackupException("worldbackup.error.catalog_write", failure);
        }
    }

    public BackupCatalog load(Path repository) throws BackupException {
        Path index = repository.resolve(INDEX_NAME);
        try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            JsonElement json = new JsonParser().parse(reader);
            readFormatVersion(json);
            validateStructure(json.getAsJsonObject());
            BackupCatalog catalog = gson.fromJson(json, BackupCatalog.class);
            validate(catalog);
            return catalog;
        } catch (BackupException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new BackupException("worldbackup.error.catalog_corrupt", failure);
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
                || catalog.getWorldId() == null || catalog.getWorldId().isEmpty()
                || catalog.getDisplayName() == null || catalog.getDisplayName().isEmpty()) {
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
        if (version == null || version.getId() == null || version.getId().isEmpty()
            || version.getTrigger() == null || version.getMode() == null
            || version.getArchiveName() == null || version.getArchiveName().isEmpty()
            || version.getManifest() == null) {
            throw corrupt();
        }
        for (Map.Entry<String, FileFingerprint> entry : version.getManifest().getFiles().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                || entry.getValue().getSha256() == null
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
}
