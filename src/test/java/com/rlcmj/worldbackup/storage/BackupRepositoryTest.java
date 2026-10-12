package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupResult;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BackupRepositoryTest {
    private static final long NOW = 1_700_000_000_123L;
    private static final long RESERVE = 64L * 1024L * 1024L;
    private static final long LAST_MANAGED_MILLISECOND = 253_402_300_799_999L;

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path world;
    private Path repo;
    private AtomicInteger ids;

    @Before
    public void setUp() throws Exception {
        world = temp.newFolder("world").toPath();
        repo = temp.newFolder("repo").toPath();
        ids = new AtomicInteger();
        write("level.dat", "one");
    }

    @Test
    public void firstIncrementalIsFullThenNextContainsOnlyChangesAndDeletion() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);

        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 10, 5, NOW - 10L);
        write("level.dat", "two-two");
        write("new.dat", "new");
        BackupCatalog beforeDeletion = new CatalogStore().load(repo);
        assertEquals(BackupMode.FULL, beforeDeletion.getVersions().get(0).getMode());
        write("gone.dat", "gone");
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 10, 5, NOW - 10L);
        Files.delete(world.resolve("gone.dat"));
        BackupResult result = repository.createBackup(world, repo, "World1", "My World",
            BackupMode.INCREMENTAL, BackupTrigger.EXIT, 10, 5, NOW - 10L);

        BackupCatalog catalog = new CatalogStore().load(repo);
        BackupVersion first = catalog.getVersions().get(0);
        BackupVersion second = catalog.getVersions().get(1);
        BackupVersion third = catalog.getVersions().get(2);
        assertEquals(BackupMode.FULL, first.getMode());
        assertEquals(BackupMode.INCREMENTAL, second.getMode());
        assertEquals(Arrays.asList("gone.dat", "level.dat", "new.dat"),
            second.getChangedPaths());
        assertEquals(Collections.singletonList("gone.dat"), third.getDeletedPaths());
        assertEquals(Collections.emptyList(), third.getChangedPaths());
        assertEquals(second.getId(), third.getParentId());
        assertEquals(first.getId(), third.getBaseId());
        assertEquals(result.getVersionId(), third.getId());
        assertEquals(Collections.singletonList("manifest.json"), zipEntries(third));
    }

    @Test
    public void createsNewFullAfterFiveDeltas() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 20, 5, NOW);
        for (int change = 1; change <= 6; change++) {
            write("level.dat", "change-" + change + repeat('x', change));
            repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
                BackupTrigger.MANUAL, 20, 5, NOW);
        }

        List<BackupVersion> versions = new CatalogStore().load(repo).getVersions();
        assertEquals(7, versions.size());
        for (int index = 1; index <= 5; index++) {
            assertEquals(BackupMode.INCREMENTAL, versions.get(index).getMode());
        }
        assertEquals(BackupMode.FULL, versions.get(6).getMode());
        assertEquals(null, versions.get(6).getParentId());
    }

    @Test
    public void unchangedIncrementalStillCreatesVisibleManifestOnlyVersion() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 10, 5, NOW);

        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.EXIT, 10, 5, NOW);

        List<BackupVersion> versions = new CatalogStore().load(repo).getVersions();
        BackupVersion unchanged = versions.get(1);
        assertTrue(unchanged.isVisible());
        assertEquals(BackupMode.INCREMENTAL, unchanged.getMode());
        assertEquals(Collections.emptyList(), unchanged.getChangedPaths());
        assertEquals(Collections.emptyList(), unchanged.getDeletedPaths());
        assertEquals(Collections.singletonList("manifest.json"), zipEntries(unchanged));
    }

    @Test
    public void fullModeAlwaysCreatesIndependentArchives() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.EXIT, 10, 5, NOW);

        List<BackupVersion> versions = new CatalogStore().load(repo).getVersions();
        assertEquals(2, versions.size());
        assertEquals(null, versions.get(0).getParentId());
        assertEquals(null, versions.get(1).getParentId());
        assertNotEquals(versions.get(0).getArchiveName(), versions.get(1).getArchiveName());
    }

    @Test
    public void overwriteReplacesOnlyItsPreviousVersionAndArchive() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.OVERWRITE,
            BackupTrigger.MANUAL, 10, 5, NOW);
        BackupCatalog before = new CatalogStore().load(repo);
        BackupVersion oldOverwrite = before.getVersions().stream()
            .filter(version -> version.getMode() == BackupMode.OVERWRITE).findFirst().get();
        write("level.dat", "replacement");
        repository.createBackup(world, repo, "World1", "My World", BackupMode.OVERWRITE,
            BackupTrigger.EXIT, 10, 5, NOW);

        BackupCatalog after = new CatalogStore().load(repo);
        assertEquals(2, after.getVersions().size());
        assertEquals(1, after.getVersions().stream()
            .filter(version -> version.getMode() == BackupMode.OVERWRITE).count());
        assertEquals(1, after.getVersions().stream()
            .filter(version -> version.getMode() == BackupMode.FULL).count());
        assertFalse(Files.exists(repo.resolve(oldOverwrite.getArchiveName())));
    }

    @Test
    public void insufficientSpaceDoesNotChangeCatalogOrArchivesAndBoundaryIsStrict() throws Exception {
        BackupRepository good = repository(Long.MAX_VALUE);
        good.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        byte[] originalIndex = Files.readAllBytes(repo.resolve("index.json"));
        List<String> originalFiles = repositoryFiles();
        write("level.dat", "12345");
        BackupRepository insufficient = repository(5L + RESERVE);

        BackupException failure = assertThrows(BackupException.class,
            () -> insufficient.createBackup(world, repo, "World1", "My World",
                BackupMode.INCREMENTAL, BackupTrigger.MANUAL, 10, 5, NOW));

        assertEquals("worldbackup.error.disk_space", failure.getTranslationKey());
        assertTrue(Arrays.equals(originalIndex, Files.readAllBytes(repo.resolve("index.json"))));
        assertEquals(originalFiles, repositoryFiles());
    }

    @Test
    public void removesOnlyUnreferencedManagedArchivesBeforeSpacePreflight() throws Exception {
        BackupRepository initial = repository(Long.MAX_VALUE);
        initial.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        BackupVersion referenced = new CatalogStore().load(repo).getVersions().get(0);
        Path referencedArchive = repo.resolve(referenced.getArchiveName());
        Path orphan = repo.resolve("20231114-221320-123-deadbeef-full.zip");
        Path unmanaged = repo.resolve("keep-me.zip");
        Files.write(orphan, new byte[] { 1 });
        Files.write(unmanaged, new byte[] { 2 });

        BackupRepository recovering = new BackupRepository(new ArchiveStore(),
            new CatalogStore(), new SnapshotScanner(), new RetentionPlanner(), path -> {
                assertFalse(Files.exists(orphan));
                assertTrue(Files.exists(referencedArchive));
                assertTrue(Files.exists(unmanaged));
                return Long.MAX_VALUE;
            }, () -> NOW, () -> String.format("%08x", ids.incrementAndGet()));

        recovering.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.EXIT, 10, 5, NOW);

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(referencedArchive));
        assertTrue(Files.exists(unmanaged));
    }

    @Test
    public void catalogFailureDeletesNewArchiveAndPreservesOldState() throws Exception {
        BackupRepository good = repository(Long.MAX_VALUE);
        good.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        byte[] originalIndex = Files.readAllBytes(repo.resolve("index.json"));
        List<String> originalFiles = repositoryFiles();
        write("level.dat", "changed");
        CatalogStore failingStore = new CatalogStore((target, writer, beforePublish) -> {
            throw new IOException("simulated catalog failure");
        });
        BackupRepository failing = repository(Long.MAX_VALUE, new ArchiveStore(), failingStore);

        BackupException failure = assertThrows(BackupException.class,
            () -> failing.createBackup(world, repo, "World1", "My World",
                BackupMode.FULL, BackupTrigger.MANUAL, 1, 5, NOW));

        assertEquals("worldbackup.error.catalog_write", failure.getTranslationKey());
        assertTrue(Arrays.equals(originalIndex, Files.readAllBytes(repo.resolve("index.json"))));
        assertEquals(originalFiles, repositoryFiles());
    }

    @Test
    public void archiveFailureLeavesOldCatalogAndArchivesUntouched() throws Exception {
        BackupRepository good = repository(Long.MAX_VALUE);
        good.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        byte[] originalIndex = Files.readAllBytes(repo.resolve("index.json"));
        List<String> originalFiles = repositoryFiles();
        write("level.dat", "changed");
        ArchiveStore failingArchive = new ArchiveStore(new ArchiveStore.PublishOperations() {
            @Override public void move(Path source, Path target, CopyOption... options)
                    throws IOException {
                throw new IOException("simulated publish failure");
            }
            @Override public OutputStream newOutputStream(Path path, OpenOption... options)
                    throws IOException {
                return Files.newOutputStream(path, options);
            }
            @Override public void beforeFallbackMove(Path source) { }
        });

        BackupException failure = assertThrows(BackupException.class,
            () -> repository(Long.MAX_VALUE, failingArchive, new CatalogStore()).createBackup(
                world, repo, "World1", "My World", BackupMode.INCREMENTAL,
                BackupTrigger.MANUAL, 10, 5, NOW));

        assertEquals("worldbackup.error.archive_write", failure.getTranslationKey());
        assertTrue(Arrays.equals(originalIndex, Files.readAllBytes(repo.resolve("index.json"))));
        assertEquals(originalFiles, repositoryFiles());
        assertFalse(hasBuildingFile());
    }

    @Test
    public void overwriteArchiveFailurePreservesPreviousOverwriteTransaction() throws Exception {
        BackupRepository good = repository(Long.MAX_VALUE);
        good.createBackup(world, repo, "World1", "My World", BackupMode.OVERWRITE,
            BackupTrigger.MANUAL, 10, 5, NOW);
        byte[] originalIndex = Files.readAllBytes(repo.resolve("index.json"));
        List<String> originalFiles = repositoryFiles();
        BackupVersion previous = new CatalogStore().load(repo).getVersions().get(0);
        write("level.dat", "changed overwrite");
        ArchiveStore failingArchive = new ArchiveStore(new ArchiveStore.PublishOperations() {
            @Override public void move(Path source, Path target, CopyOption... options)
                    throws IOException {
                throw new IOException("simulated overwrite publish failure");
            }
            @Override public OutputStream newOutputStream(Path path, OpenOption... options)
                    throws IOException {
                return Files.newOutputStream(path, options);
            }
            @Override public void beforeFallbackMove(Path source) { }
        });

        BackupException failure = assertThrows(BackupException.class,
            () -> repository(Long.MAX_VALUE, failingArchive, new CatalogStore()).createBackup(
                world, repo, "World1", "My World", BackupMode.OVERWRITE,
                BackupTrigger.EXIT, 10, 5, NOW));

        assertEquals("worldbackup.error.archive_write", failure.getTranslationKey());
        assertTrue(Arrays.equals(originalIndex, Files.readAllBytes(repo.resolve("index.json"))));
        assertEquals(originalFiles, repositoryFiles());
        assertTrue(Files.exists(repo.resolve(previous.getArchiveName())));
        assertFalse(hasBuildingFile());
    }

    @Test
    public void overwriteCatalogFailurePreservesPreviousOverwriteTransaction() throws Exception {
        BackupRepository good = repository(Long.MAX_VALUE);
        good.createBackup(world, repo, "World1", "My World", BackupMode.OVERWRITE,
            BackupTrigger.MANUAL, 10, 5, NOW);
        byte[] originalIndex = Files.readAllBytes(repo.resolve("index.json"));
        List<String> originalFiles = repositoryFiles();
        BackupVersion previous = new CatalogStore().load(repo).getVersions().get(0);
        write("level.dat", "changed overwrite");
        CatalogStore failingStore = new CatalogStore((target, writer, beforePublish) -> {
            throw new IOException("simulated overwrite catalog failure");
        });

        BackupException failure = assertThrows(BackupException.class,
            () -> repository(Long.MAX_VALUE, new ArchiveStore(), failingStore).createBackup(
                world, repo, "World1", "My World", BackupMode.OVERWRITE,
                BackupTrigger.EXIT, 10, 5, NOW));

        assertEquals("worldbackup.error.catalog_write", failure.getTranslationKey());
        assertTrue(Arrays.equals(originalIndex, Files.readAllBytes(repo.resolve("index.json"))));
        assertEquals(originalFiles, repositoryFiles());
        assertTrue(Files.exists(repo.resolve(previous.getArchiveName())));
        assertFalse(hasBuildingFile());
    }

    @Test
    public void unreadableLatestArchiveStartsFreshBase() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        BackupVersion first = new CatalogStore().load(repo).getVersions().get(0);
        Files.write(repo.resolve(first.getArchiveName()), "broken".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING);
        write("level.dat", "changed");

        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 10, 5, NOW);

        BackupVersion latest = new CatalogStore().load(repo).getVersions().get(1);
        assertEquals(BackupMode.FULL, latest.getMode());
        assertEquals(null, latest.getParentId());
    }

    @Test
    public void unreadableBaseBehindReadableDeltaStartsFreshBase() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        write("level.dat", "delta");
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        BackupCatalog before = new CatalogStore().load(repo);
        BackupVersion base = before.getVersions().get(0);
        Files.write(repo.resolve(base.getArchiveName()),
            "broken".getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
        write("level.dat", "new-base");

        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.EXIT, 10, 5, NOW);

        BackupVersion latest = latestVersion(new CatalogStore().load(repo));
        assertEquals(BackupMode.FULL, latest.getMode());
        assertEquals(null, latest.getParentId());
    }

    @Test
    public void validatesCatalogIdentityAndPositiveLimitsBeforeWriting() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);
        List<String> originalFiles = repositoryFiles();

        assertThrows(BackupException.class, () -> repository.createBackup(world, repo,
            "Other", "My World", BackupMode.FULL, BackupTrigger.MANUAL, 10, 5, NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.createBackup(world, repo,
            "World1", "My World", BackupMode.FULL, BackupTrigger.MANUAL, 0, 5, NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.createBackup(world, repo,
            "World1", "My World", BackupMode.FULL, BackupTrigger.MANUAL, 10, 0, NOW));
        assertEquals(originalFiles, repositoryFiles());
    }

    @Test
    public void rejectsSymlinkAncestorBeforeCreatingDirectoryInItsTarget() throws Exception {
        Path link = temp.getRoot().toPath().resolve("repository-link");
        createSymbolicLinkOrSkip(link, world);
        Path escapedDirectory = world.resolve("must-not-be-created");

        BackupException failure = assertThrows(BackupException.class,
            () -> repository(Long.MAX_VALUE).createBackup(world,
                link.resolve("must-not-be-created"), "World1", "My World",
                BackupMode.FULL, BackupTrigger.MANUAL, 10, 5, NOW));

        assertEquals("worldbackup.error.path_escape", failure.getTranslationKey());
        assertFalse(Files.exists(escapedDirectory));
    }

    @Test
    public void rejectsWindowsJunctionAncestorBeforeCreatingDirectoryInItsTarget()
            throws Exception {
        Assume.assumeTrue("Windows junctions only", File.separatorChar == '\\');
        Path target = temp.newFolder("junction-repository-target").toPath();
        Path junction = temp.getRoot().toPath().resolve("repository-junction");
        createJunctionOrSkip(junction, target);
        Path escapedDirectory = target.resolve("must-not-be-created");

        BackupException failure = assertThrows(BackupException.class,
            () -> repository(Long.MAX_VALUE).createBackup(world,
                junction.resolve("must-not-be-created"), "World1", "My World",
                BackupMode.FULL, BackupTrigger.MANUAL, 10, 5, NOW));

        assertEquals("worldbackup.error.path_escape", failure.getTranslationKey());
        assertFalse(Files.exists(escapedDirectory));
    }

    @Test
    public void createsAndValidatesEachMissingRepositoryDirectory() throws Exception {
        Path nestedRepository = temp.getRoot().toPath()
            .resolve("nested").resolve("missing").resolve("repository");

        repository(Long.MAX_VALUE).createBackup(world, nestedRepository,
            "World1", "My World", BackupMode.FULL, BackupTrigger.MANUAL, 10, 5, NOW);

        assertTrue(Files.isDirectory(nestedRepository));
        assertTrue(Files.isRegularFile(nestedRepository.resolve("index.json")));
    }

    @Test
    public void rejectsDirectRepositorySymlink() throws Exception {
        Path target = temp.newFolder("direct-repository-target").toPath();
        Path link = temp.getRoot().toPath().resolve("direct-repository-link");
        createSymbolicLinkOrSkip(link, target);

        BackupException failure = assertThrows(BackupException.class,
            () -> repository(Long.MAX_VALUE).createBackup(world, link,
                "World1", "My World", BackupMode.FULL, BackupTrigger.MANUAL, 10, 5, NOW));

        assertEquals("worldbackup.error.path_escape", failure.getTranslationKey());
        assertEquals(Collections.emptyList(), directoryEntries(target));
    }

    @Test
    public void acceptsRenamedDisplayNameForTheSameWorldId() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "Old Name", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);

        repository.createBackup(world, repo, "World1", "Renamed World", BackupMode.FULL,
            BackupTrigger.EXIT, 10, 5, NOW);

        BackupCatalog catalog = new CatalogStore().load(repo);
        assertEquals("Renamed World", catalog.getDisplayName());
        assertEquals(2, catalog.getVersions().size());
    }

    @Test
    public void generatesUtcTimestampedNamesWithEightHexCharacters() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);

        BackupVersion version = new CatalogStore().load(repo).getVersions().get(0);
        assertTrue(version.getId().matches("20231114-221320-123-[0-9a-f]{8}"));
        assertEquals(version.getId() + "-full.zip", version.getArchiveName());
    }

    @Test
    public void rejectsTimestampsThatCannotUseManagedArchiveNames() throws Exception {
        BackupRepository lastValid = new BackupRepository(new ArchiveStore(),
            new CatalogStore(), new SnapshotScanner(), new RetentionPlanner(),
            path -> Long.MAX_VALUE, () -> LAST_MANAGED_MILLISECOND,
            () -> String.format("%08x", ids.incrementAndGet()));

        lastValid.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, LAST_MANAGED_MILLISECOND);

        BackupVersion version = new CatalogStore().load(repo).getVersions().get(0);
        assertTrue(version.getId().matches("99991231-235959-999-[0-9a-f]{8}"));
        List<String> before = repositoryFiles();
        assertThrows(IllegalArgumentException.class, () -> lastValid.createBackup(world, repo,
            "World1", "My World", BackupMode.FULL, BackupTrigger.EXIT, 10, 5,
            LAST_MANAGED_MILLISECOND));
        assertThrows(IllegalArgumentException.class, () -> repository(Long.MAX_VALUE)
            .createBackup(world, repo, "World1", "My World", BackupMode.FULL,
                BackupTrigger.EXIT, 10, 5, LAST_MANAGED_MILLISECOND + 1L));
        assertEquals(before, repositoryFiles());
    }

    @Test
    public void retriesRandomSuffixCollisionWithinTheSameMillisecond() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BackupRepository repository = new BackupRepository(new ArchiveStore(),
            new CatalogStore(), new SnapshotScanner(), new RetentionPlanner(),
            path -> Long.MAX_VALUE, () -> NOW,
            () -> attempts.getAndIncrement() == 0 ? "00000001" : "00000002");
        Files.createDirectory(repo.resolve("20231114-221320-123-00000001-full.zip"));

        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 10, 5, NOW);

        List<BackupVersion> versions = new CatalogStore().load(repo).getVersions();
        assertEquals(1, versions.size());
        assertEquals("20231114-221320-123-00000002", versions.get(0).getId());
        assertEquals(2, attempts.get());
    }

    @Test
    public void serializesConcurrentRequestsWithoutLosingEitherVersion() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BackupResult> first = workers.submit(() -> {
                start.await();
                return repository.createBackup(world, repo, "World1", "My World",
                    BackupMode.FULL, BackupTrigger.MANUAL, 10, 5, NOW);
            });
            Future<BackupResult> second = workers.submit(() -> {
                start.await();
                return repository.createBackup(world, repo, "World1", "My World",
                    BackupMode.FULL, BackupTrigger.EXIT, 10, 5, NOW);
            });

            start.countDown();
            assertNotEquals(first.get().getVersionId(), second.get().getVersionId());
        } finally {
            workers.shutdownNow();
        }

        assertEquals(2, new CatalogStore().load(repo).getVersions().size());
    }

    @Test
    public void serializesConcurrentRequestsAcrossRepositoryInstances() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        BackupRepository.SpaceProbe slowProbe = path -> {
            int current = active.incrementAndGet();
            maximumActive.updateAndGet(previous -> Math.max(previous, current));
            try {
                Thread.sleep(200L);
                return Long.MAX_VALUE;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", failure);
            } finally {
                active.decrementAndGet();
            }
        };
        BackupRepository firstRepository = new BackupRepository(new ArchiveStore(),
            new CatalogStore(), new SnapshotScanner(), new RetentionPlanner(), slowProbe,
            () -> NOW, () -> "00000001");
        BackupRepository secondRepository = new BackupRepository(new ArchiveStore(),
            new CatalogStore(), new SnapshotScanner(), new RetentionPlanner(), slowProbe,
            () -> NOW, () -> "00000002");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BackupResult> first = workers.submit(() -> {
                start.await();
                return firstRepository.createBackup(world, repo, "World1", "My World",
                    BackupMode.FULL, BackupTrigger.MANUAL, 10, 5, NOW);
            });
            Future<BackupResult> second = workers.submit(() -> {
                start.await();
                return secondRepository.createBackup(world, repo, "World1", "My World",
                    BackupMode.FULL, BackupTrigger.EXIT, 10, 5, NOW);
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            workers.shutdownNow();
        }

        assertEquals(1, maximumActive.get());
        assertEquals(2, new CatalogStore().load(repo).getVersions().size());
    }

    @Test
    public void clockRollbackCannotDiscardTheJustCreatedVersion() throws Exception {
        long[] times = { NOW, NOW, NOW - 1_000L, NOW };
        AtomicInteger calls = new AtomicInteger();
        BackupRepository repository = new BackupRepository(new ArchiveStore(),
            new CatalogStore(), new SnapshotScanner(), new RetentionPlanner(),
            path -> Long.MAX_VALUE,
            () -> times[Math.min(calls.getAndIncrement(), times.length - 1)],
            () -> String.format("%08x", ids.incrementAndGet()));
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 1, 5, NOW);

        BackupResult result = repository.createBackup(world, repo, "World1", "My World",
            BackupMode.FULL, BackupTrigger.EXIT, 1, 5, NOW);

        BackupCatalog catalog = new CatalogStore().load(repo);
        assertEquals(1, catalog.getVersions().size());
        assertEquals(result.getVersionId(), catalog.getVersions().get(0).getId());
    }

    @Test
    public void committedBackupRemainsSuccessfulWhenOldArchiveCleanupFails() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 1, 5, NOW);
        BackupVersion old = new CatalogStore().load(repo).getVersions().get(0);
        Path undeletable = repo.resolve(old.getArchiveName());
        Files.delete(undeletable);
        Files.createDirectory(undeletable);
        Files.write(undeletable.resolve("still-in-use"), new byte[] { 1 });

        BackupResult result = repository.createBackup(world, repo, "World1", "My World",
            BackupMode.FULL, BackupTrigger.EXIT, 1, 5, NOW);

        BackupCatalog catalog = new CatalogStore().load(repo);
        assertEquals(1, catalog.getVersions().size());
        assertEquals(result.getVersionId(), catalog.getVersions().get(0).getId());
        assertTrue(Files.isDirectory(undeletable));

        Files.delete(undeletable.resolve("still-in-use"));
        Files.delete(undeletable);
        Files.write(undeletable, new byte[] { 1 });
        write("level.dat", "one-more-backup");
        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.MANUAL, 1, 5, NOW);

        assertFalse(Files.exists(undeletable));
    }

    @Test
    public void keepsChainDependenciesHiddenThenDeletesThemWhenUnreachable() throws Exception {
        BackupRepository repository = repository(Long.MAX_VALUE);
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 1, 5, NOW);
        write("level.dat", "delta-one");
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 1, 5, NOW);
        write("level.dat", "delta-two");
        repository.createBackup(world, repo, "World1", "My World", BackupMode.INCREMENTAL,
            BackupTrigger.MANUAL, 1, 5, NOW);

        BackupCatalog chained = new CatalogStore().load(repo);
        assertEquals(3, chained.getVersions().size());
        assertEquals(1L, chained.getVersions().stream()
            .filter(BackupVersion::isVisible).count());
        List<String> oldArchives = new ArrayList<>();
        for (BackupVersion version : chained.getVersions()) {
            oldArchives.add(version.getArchiveName());
            assertTrue(Files.exists(repo.resolve(version.getArchiveName())));
        }

        repository.createBackup(world, repo, "World1", "My World", BackupMode.FULL,
            BackupTrigger.EXIT, 1, 5, NOW);

        BackupCatalog replaced = new CatalogStore().load(repo);
        assertEquals(1, replaced.getVersions().size());
        assertEquals(BackupMode.FULL, replaced.getVersions().get(0).getMode());
        for (String oldArchive : oldArchives) {
            assertFalse(Files.exists(repo.resolve(oldArchive)));
        }
    }

    private BackupRepository repository(long usableBytes) {
        return repository(usableBytes, new ArchiveStore(), new CatalogStore());
    }

    private BackupRepository repository(long usableBytes, ArchiveStore archiveStore,
                                          CatalogStore catalogStore) {
        return new BackupRepository(archiveStore, catalogStore, new SnapshotScanner(),
            new RetentionPlanner(), path -> usableBytes, () -> NOW,
            () -> String.format("%08x", ids.incrementAndGet()));
    }

    private void write(String relative, String contents) throws IOException {
        Path path = world.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, contents.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> repositoryFiles() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(repo)) {
            files.forEach(path -> names.add(path.getFileName().toString()));
        }
        Collections.sort(names);
        return names;
    }

    private boolean hasBuildingFile() throws IOException {
        try (Stream<Path> files = Files.list(repo)) {
            return files.anyMatch(path -> path.getFileName().toString()
                .endsWith(".building"));
        }
    }

    private static List<String> directoryEntries(Path directory) throws IOException {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.map(path -> path.getFileName().toString()).forEach(entries::add);
        }
        Collections.sort(entries);
        return entries;
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }
    }

    private static void createJunctionOrSkip(Path junction, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
            junction.toString(), target.toString()).redirectErrorStream(true).start();
        try (InputStream output = process.getInputStream()) {
            byte[] buffer = new byte[256];
            while (output.read(buffer) >= 0) {
                // Drain command output so the process cannot block.
            }
        }
        int exitCode = process.waitFor();
        Assume.assumeTrue("Directory junctions are unavailable", exitCode == 0);
    }

    private static BackupVersion latestVersion(BackupCatalog catalog) {
        return catalog.getVersions().get(catalog.getVersions().size() - 1);
    }

    private List<String> zipEntries(BackupVersion version) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(repo.resolve(version.getArchiveName()).toFile())) {
            zip.stream().map(ZipEntry::getName).forEach(names::add);
        }
        Collections.sort(names);
        return names;
    }

    private static String repeat(char character, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, character);
        return new String(chars);
    }
}
