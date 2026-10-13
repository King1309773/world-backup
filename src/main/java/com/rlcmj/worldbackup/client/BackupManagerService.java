package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.PortablePaths;
import com.rlcmj.worldbackup.storage.CatalogStore;
import com.rlcmj.worldbackup.storage.RetentionPlanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BackupManagerService {
    private final CatalogStore catalogs;
    private final RetentionPlanner retention;

    public BackupManagerService() {
        this(new CatalogStore(), new RetentionPlanner());
    }

    BackupManagerService(CatalogStore catalogs, RetentionPlanner retention) {
        this.catalogs = catalogs;
        this.retention = retention;
    }

    public BackupCatalog load(Path repository, String expectedWorldId)
            throws BackupException {
        BackupCatalog catalog = catalogs.load(requireRepository(repository));
        validateIdentity(catalog, expectedWorldId);
        return catalog;
    }

    public DeleteResult delete(Path repository, String expectedWorldId,
                               String targetVersionId)
            throws BackupException {
        Path repo = requireRepository(repository);
        BackupCatalog source = catalogs.load(repo);
        validateIdentity(source, expectedWorldId);
        Set<String> retainedVisible = new LinkedHashSet<>();
        boolean found = false;
        for (BackupVersion version : source.getVersions()) {
            if (version.isVisible() && version.getId().equals(targetVersionId)) found = true;
            else if (version.isVisible()) retainedVisible.add(version.getId());
        }
        if (!found) throw new BackupException("worldbackup.error.restore_chain");
        RetentionPlanner.Plan plan = retention.planKeepingVisibleIds(source, retainedVisible);
        catalogs.save(repo, plan.getCatalog());

        List<String> warnings = new ArrayList<>();
        for (String archiveName : plan.getArchiveNamesToDelete()) {
            try {
                String safeName = PortablePaths.requireSafeBasename(archiveName);
                Path archive = repo.resolve(safeName).toAbsolutePath().normalize();
                if (!archive.getParent().equals(repo)) throw new IOException("archive escaped");
                if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) continue;
                BasicFileAttributes attributes = Files.readAttributes(archive,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isOther()
                        || Files.isSymbolicLink(archive)) {
                    throw new IOException("archive is not a direct regular file");
                }
                Files.delete(archive);
            } catch (IOException | RuntimeException failure) {
                warnings.add(archiveName);
            }
        }
        return new DeleteResult(plan.getCatalog(), warnings);
    }

    private static void validateIdentity(BackupCatalog catalog, String expectedWorldId)
            throws BackupException {
        if (expectedWorldId == null || !expectedWorldId.equals(catalog.getWorldId())) {
            throw new BackupException("worldbackup.error.catalog_mismatch");
        }
    }

    private static Path requireRepository(Path repository) throws BackupException {
        try {
            Path repo = repository.toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(repo,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isOther()
                    || Files.isSymbolicLink(repo)) {
                throw new IOException("unsafe repository");
            }
            return repo;
        } catch (IOException | RuntimeException failure) {
            throw new BackupException("worldbackup.error.path_escape", failure);
        }
    }

    public static final class DeleteResult {
        private final BackupCatalog catalog;
        private final List<String> warnings;

        private DeleteResult(BackupCatalog catalog, List<String> warnings) {
            this.catalog = catalog;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public BackupCatalog getCatalog() { return catalog; }
        public List<String> getWarnings() { return warnings; }
    }
}
