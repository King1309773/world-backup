package com.rlcmj.worldbackup.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
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
            int formatVersion = readFormatVersion(json);
            if (formatVersion != BackupCatalog.FORMAT_VERSION) {
                throw new BackupException("worldbackup.error.catalog_version",
                    formatVersion);
            }
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

    private static int readFormatVersion(JsonElement json) throws BackupException {
        if (json == null || !json.isJsonObject()) {
            throw corrupt();
        }
        JsonElement version = required(json.getAsJsonObject(), "formatVersion");
        if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
            throw corrupt();
        }
        return version.getAsInt();
    }

    private static void validateStructure(JsonObject catalog) throws BackupException {
        required(catalog, "worldId");
        required(catalog, "displayName");
        JsonArray versions = requiredArray(catalog, "versions");
        for (JsonElement value : versions) {
            if (!value.isJsonObject()) {
                throw corrupt();
            }
            validateVersionStructure(value.getAsJsonObject());
        }
    }

    private static void validateVersionStructure(JsonObject version) throws BackupException {
        String[] fields = {"id", "createdAt", "trigger", "mode", "archiveName", "visible",
            "originalBytes", "archiveBytes", "durationMillis", "manifest", "changedPaths",
            "deletedPaths"};
        for (String field : fields) {
            required(version, field);
        }
        requiredArray(version, "changedPaths");
        requiredArray(version, "deletedPaths");
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
            required(fingerprint, "size");
            required(fingerprint, "modifiedTimeMillis");
            required(fingerprint, "sha256");
        }
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
