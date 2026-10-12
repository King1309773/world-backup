package com.rlcmj.worldbackup.storage;

import com.rlcmj.worldbackup.core.BackupCancellation;
import com.rlcmj.worldbackup.core.BackupCatalog;
import com.rlcmj.worldbackup.core.BackupException;
import com.rlcmj.worldbackup.core.BackupMode;
import com.rlcmj.worldbackup.core.BackupTrigger;
import com.rlcmj.worldbackup.core.BackupVersion;
import com.rlcmj.worldbackup.core.FileFingerprint;
import com.rlcmj.worldbackup.core.SnapshotManifest;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BackupRestorerTest {
    private static final long RESTORE_TIME = Instant.parse("2026-10-09T01:02:03.004Z")
        .toEpochMilli();

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void restoresBaseAndTwoDeltasAndPreservesOriginalAsSafetyCopy() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        Path restored = fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
            "World1", fixture.repo, chain.v3.getId());

        assertArrayEquals(bytes("newest"), Files.readAllBytes(restored.resolve("level.dat")));
        assertArrayEquals(bytes("added"), Files.readAllBytes(restored.resolve("added.dat")));
        assertFalse(Files.exists(restored.resolve("deleted.dat")));
        Path safety = fixture.gameDir.resolve("restore-safety")
            .resolve("World1-20261009-010203-004");
        assertArrayEquals(bytes("current"), Files.readAllBytes(safety.resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id")));
    }

    @Test
    public void availabilityValidationReadsEntireDependencyChainWithoutChangingWorld()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        byte[] before = Files.readAllBytes(fixture.world.resolve("current.dat"));

        fixture.restorer().validateAvailability(fixture.repo, "World1", chain.v3.getId());

        assertArrayEquals(before, Files.readAllBytes(fixture.world.resolve("current.dat")));
        Files.write(fixture.repo.resolve(chain.v2.getArchiveName()), new byte[] { 1, 2, 3 });
        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer().validateAvailability(
                fixture.repo, "World1", chain.v3.getId()));
        assertEquals("worldbackup.error.archive_corrupt", error.getTranslationKey());
    }

    @Test
    public void restoresAnOlderTargetUsingHiddenDependenciesInsteadOfLatestVersion()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(false);
        BackupVersion visibleV2 = version("v2", BackupMode.INCREMENTAL, "v2.zip", "v1", "v1",
            true, chain.v2.getManifest(), chain.v2.getChangedPaths(), chain.v2.getDeletedPaths());
        SnapshotManifest v4Manifest = manifest("level.dat", "future");
        fixture.archive("v4.zip", v4Manifest, Collections.singletonList("level.dat"),
            Collections.<String>emptyList());
        BackupVersion v4 = version("v4", BackupMode.INCREMENTAL, "v4.zip", "v3", "v1",
            true, v4Manifest, Collections.singletonList("level.dat"),
            Collections.<String>emptyList());
        fixture.saveCatalog(chain.v1, visibleV2, chain.v3, v4);

        Path restored = fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
            "World1", fixture.repo, "v2");

        assertArrayEquals(bytes("new"), Files.readAllBytes(restored.resolve("level.dat")));
        assertArrayEquals(bytes("added"), Files.readAllBytes(restored.resolve("added.dat")));
    }

    @Test
    public void refusesHiddenVersionAsRestoreTarget() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(false);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v2.getId()));

        assertEquals("worldbackup.error.restore_chain", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.world.resolve("current.dat")));
    }

    @Test
    public void restoresWhenWorldDoesNotExistWithoutCreatingSafetyCopy() throws Exception {
        Fixture fixture = fixture(false);
        Chain chain = fixture.chain(true);

        Path restored = fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
            "World1", fixture.repo, chain.v1.getId());

        assertArrayEquals(bytes("old"), Files.readAllBytes(restored.resolve("level.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("restore-safety")));
    }

    @Test
    public void rejectsMissingParentCycleAndIncompatibleChainBeforeMovingWorld()
            throws Exception {
        for (String defect : Arrays.asList("missing", "cycle", "wrong-world")) {
            Fixture fixture = fixture();
            SnapshotManifest state = manifest("level.dat", "value");
            fixture.archive("delta.zip", state, Collections.singletonList("level.dat"),
                Collections.<String>emptyList());
            BackupVersion target;
            BackupCatalog catalog;
            if ("wrong-world".equals(defect)) {
                target = version("target", BackupMode.FULL, "delta.zip", null, null, true,
                    state, Collections.<String>emptyList(), Collections.<String>emptyList());
                catalog = BackupCatalog.empty("OtherWorld", "Other World");
            } else {
                String parent = "missing".equals(defect) ? "absent" : "target";
                target = version("target", BackupMode.INCREMENTAL, "delta.zip", parent, "target",
                    true, state, Collections.singletonList("level.dat"),
                    Collections.<String>emptyList());
                catalog = BackupCatalog.empty("World1", "World One");
            }
            catalog.add(target);
            new CatalogStore().save(fixture.repo, catalog);

            BackupException error = assertThrows(BackupException.class,
                () -> fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
                    "World1", fixture.repo, "target"));

            assertTrue(error.getTranslationKey().startsWith("worldbackup.error."));
            assertArrayEquals(bytes("current"), Files.readAllBytes(
                fixture.world.resolve("current.dat")));
            assertFalse(Files.exists(fixture.gameDir.resolve("restore-safety")));
        }
    }

    @Test
    public void rejectsIncrementalChainWhoseBaseIdentifierDoesNotMatch() throws Exception {
        Fixture fixture = fixture();
        SnapshotManifest state = manifest("level.dat", "value");
        fixture.archive("base.zip", state, Collections.singletonList("level.dat"),
            Collections.<String>emptyList());
        fixture.archive("delta.zip", state, Collections.<String>emptyList(),
            Collections.<String>emptyList());
        BackupVersion base = version("base", BackupMode.FULL, "base.zip", null, null, false,
            state, Collections.<String>emptyList(), Collections.<String>emptyList());
        BackupVersion target = version("target", BackupMode.INCREMENTAL, "delta.zip", "base",
            "different-base", true, state, Collections.<String>emptyList(),
            Collections.<String>emptyList());
        fixture.saveCatalog(base, target);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, "target"));

        assertEquals("worldbackup.error.restore_chain", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.world.resolve("current.dat")));
    }

    @Test
    public void rejectsCorruptArchiveAndMissingDeltaPayloadBeforeMovingWorld()
            throws Exception {
        Fixture corrupt = fixture();
        Chain corruptChain = corrupt.chain(true);
        Path archive = corrupt.repo.resolve(corruptChain.v2.getArchiveName());
        byte[] encoded = Files.readAllBytes(archive);
        Files.write(archive, Arrays.copyOf(encoded, encoded.length / 2));

        BackupException corruptError = assertThrows(BackupException.class,
            () -> corrupt.restorer().restore(corrupt.gameDir, corrupt.savesDir,
                "World1", corrupt.repo, "v3"));
        assertEquals("worldbackup.error.archive_corrupt", corruptError.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            corrupt.world.resolve("current.dat")));

        Fixture mismatch = fixture();
        Chain mismatchChain = mismatch.chain(true);
        Files.delete(mismatch.repo.resolve("v3.zip"));
        mismatch.archive("v3.zip", mismatchChain.v3.getManifest(),
            Collections.<String>emptyList(), Collections.<String>emptyList());

        BackupException mismatchError = assertThrows(BackupException.class,
            () -> mismatch.restorer().restore(mismatch.gameDir, mismatch.savesDir,
                "World1", mismatch.repo, "v3"));
        assertEquals("worldbackup.error.restore_chain", mismatchError.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            mismatch.world.resolve("current.dat")));
    }

    @Test
    public void rejectsArchiveAndCatalogDeltaMetadataMismatchEvenIfFinalFilesMatch()
            throws Exception {
        Fixture fixture = fixture();
        SnapshotManifest state = manifest("level.dat", "value");
        fixture.archive("base.zip", state, Collections.singletonList("level.dat"),
            Collections.<String>emptyList());
        fixture.archive("delta.zip", state, Collections.<String>emptyList(),
            Collections.<String>emptyList());
        BackupVersion base = version("base", BackupMode.FULL, "base.zip", null, null, false,
            state, Collections.<String>emptyList(), Collections.<String>emptyList());
        BackupVersion inconsistent = version("target", BackupMode.INCREMENTAL, "delta.zip",
            "base", "base", true, state, Collections.<String>emptyList(),
            Collections.singletonList("ghost.dat"));
        fixture.saveCatalog(base, inconsistent);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, "target"));

        assertEquals("worldbackup.error.restore_chain", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.world.resolve("current.dat")));
    }

    @Test
    public void restoresIndependentOverwriteVersion() throws Exception {
        Fixture fixture = fixture(false);
        SnapshotManifest state = manifest("level.dat", "value");
        fixture.archive("latest.zip", state, Collections.singletonList("level.dat"),
            Collections.<String>emptyList());
        BackupVersion overwrite = version("latest", BackupMode.OVERWRITE, "latest.zip", null,
            null, true, state, Collections.<String>emptyList(),
            Collections.<String>emptyList());
        fixture.saveCatalog(overwrite);

        Path restored = fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
            "World1", fixture.repo, "latest");

        assertArrayEquals(bytes("value"), Files.readAllBytes(restored.resolve("level.dat")));
    }

    @Test
    public void refusesUnsafeDeletionAndCatalogArchivePaths() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        Path index = fixture.repo.resolve("index.json");
        String json = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
        Files.write(index, json.replace("\"deleted.dat\"", "\"../outside.dat\"")
            .getBytes(StandardCharsets.UTF_8));

        BackupException deletion = assertThrows(BackupException.class,
            () -> fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v3.getId()));
        assertEquals("worldbackup.error.catalog_corrupt", deletion.getTranslationKey());
        assertArrayEquals(bytes("outside"), Files.readAllBytes(
            fixture.gameDir.resolve("outside.dat")));

        Fixture archivePath = fixture();
        Chain safe = archivePath.chain(true);
        Path archiveIndex = archivePath.repo.resolve("index.json");
        String archiveJson = new String(Files.readAllBytes(archiveIndex), StandardCharsets.UTF_8);
        Files.write(archiveIndex, archiveJson.replace("v3.zip", "../v3.zip")
            .getBytes(StandardCharsets.UTF_8));
        BackupException escaped = assertThrows(BackupException.class,
            () -> archivePath.restorer().restore(archivePath.gameDir, archivePath.savesDir,
                "World1", archivePath.repo, safe.v3.getId()));
        assertEquals("worldbackup.error.catalog_corrupt", escaped.getTranslationKey());
    }

    @Test
    public void rejectsRepositoryAndArchiveSymbolicLinks() throws Exception {
        Fixture repositoryFixture = fixture();
        Chain chain = repositoryFixture.chain(true);
        Path repositoryLink = repositoryFixture.gameDir.resolve("repo-link");
        try {
            Files.createSymbolicLink(repositoryLink, repositoryFixture.repo);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        BackupException repositoryError = assertThrows(BackupException.class,
            () -> repositoryFixture.restorer().restore(repositoryFixture.gameDir,
                repositoryFixture.savesDir, "World1", repositoryLink, chain.v3.getId()));
        assertEquals("worldbackup.error.path_escape", repositoryError.getTranslationKey());

        Fixture archiveFixture = fixture();
        Chain archiveChain = archiveFixture.chain(true);
        Path outside = temp.newFile("outside-archive.zip").toPath();
        Files.copy(archiveFixture.repo.resolve("v3.zip"), outside,
            StandardCopyOption.REPLACE_EXISTING);
        Files.delete(archiveFixture.repo.resolve("v3.zip"));
        Files.createSymbolicLink(archiveFixture.repo.resolve("v3.zip"), outside);

        BackupException archiveError = assertThrows(BackupException.class,
            () -> archiveFixture.restorer().restore(archiveFixture.gameDir,
                archiveFixture.savesDir, "World1", archiveFixture.repo,
                archiveChain.v3.getId()));
        assertEquals("worldbackup.error.path_escape", archiveError.getTranslationKey());
    }

    @Test
    public void rejectsCatalogSymbolicLinkPointingOutsideRepository() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        Path index = fixture.repo.resolve("index.json");
        Path outside = fixture.gameDir.resolve("outside-index.json");
        Files.copy(index, outside, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(index);
        try {
            Files.createSymbolicLink(index, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v3.getId()));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.world.resolve("current.dat")));
    }

    @Test
    public void ignoresRootSessionLockFromArchiveAndFinalManifest() throws Exception {
        Fixture fixture = fixture();
        SnapshotManifest snapshot = manifest("level.dat", "restored", "session.lock", "lock");
        fixture.archive("lock.zip", snapshot, Arrays.asList("level.dat", "session.lock"),
            Collections.<String>emptyList());
        BackupVersion version = version("lock", BackupMode.FULL, "lock.zip", null, null,
            true, snapshot, Collections.<String>emptyList(), Collections.<String>emptyList());
        fixture.saveCatalog(version);

        Path restored = fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
            "World1", fixture.repo, "lock");

        assertArrayEquals(bytes("restored"), Files.readAllBytes(restored.resolve("level.dat")));
        assertFalse(Files.exists(restored.resolve("session.lock"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void promotionFailureRollsBackOriginalAndCleansTemporaryWorld() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        InjectedMoves moves = new InjectedMoves(InjectedMoves.Failure.PROMOTION);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(moves).restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v3.getId()));

        assertEquals("worldbackup.error.restore_swap", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.world.resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("restore-safety")
            .resolve("World1-20261009-010203-004")));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id")));
    }

    @Test
    public void promotionFailureBeforeMoveWithoutOriginalIsSwapFailureAndCleansTemporary()
            throws Exception {
        Fixture fixture = fixture(false);
        Chain chain = fixture.chain(true);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(new InjectedMoves(InjectedMoves.Failure.PROMOTION))
                .restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
                    chain.v3.getId()));

        assertEquals("worldbackup.error.restore_swap", error.getTranslationKey());
        assertFalse(Files.exists(fixture.world, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void safetyMoveFailureLeavesOriginalUntouchedAndCleansTemporaryWorld()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        InjectedMoves moves = new InjectedMoves(InjectedMoves.Failure.SAFETY);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(moves).restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v3.getId()));

        assertEquals("worldbackup.error.restore_swap", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.world.resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id")));
    }

    @Test
    public void rollbackFailurePreservesVerifiedTemporaryWorldAndReportsItsPath()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        InjectedMoves moves = new InjectedMoves(InjectedMoves.Failure.PROMOTION_AND_ROLLBACK);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(moves).restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v3.getId()));

        Path recovery = fixture.gameDir.resolve("worldbackup-tmp").resolve("World1-fixed-id")
            .toAbsolutePath().normalize();
        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertTrue(Files.isDirectory(recovery));
        assertArrayEquals(bytes("newest"), Files.readAllBytes(recovery.resolve("level.dat")));
        assertNotNull(error.getCause());
        assertTrue(error.getCause().getMessage().contains(recovery.toString()));
    }

    @Test
    public void revalidatesTargetParentBeforeFallbackMoveAfterReparseReplacement()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        Path outside = Files.createDirectories(temp.newFolder("outside-saves").toPath());
        BackupRestorer.MoveOperations race = new BackupRestorer.MoveOperations() {
            private boolean replaced;

            @Override
            public void move(Path source, Path target, CopyOption... options)
                    throws IOException {
                boolean atomic = Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE);
                boolean promotion = source.toString().contains("worldbackup-tmp");
                if (atomic && promotion && !replaced) {
                    replaced = true;
                    Path realSaves = fixture.gameDir.resolve("saves-before-race");
                    Files.move(fixture.savesDir, realSaves);
                    try {
                        Files.createSymbolicLink(fixture.savesDir, outside);
                    } catch (IOException | UnsupportedOperationException
                             | SecurityException unavailable) {
                        Files.move(realSaves, fixture.savesDir);
                        Assume.assumeNoException("Symbolic links are unavailable", unavailable);
                    }
                    throw new AtomicMoveNotSupportedException(source.toString(),
                        target.toString(), "injected parent replacement");
                }
                if (atomic) {
                    throw new AtomicMoveNotSupportedException(source.toString(),
                        target.toString(), "exercise fallback");
                }
                Files.move(source, target, options);
            }
        };

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(race).restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v3.getId()));

        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertFalse(Files.exists(outside.resolve("World1"), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(bytes("current"), Files.readAllBytes(fixture.gameDir
            .resolve("restore-safety").resolve("World1-20261009-010203-004")
            .resolve("current.dat")));
    }

    @Test
    public void safetyMoveThatCompletesBeforeThrowIsTreatedAsMoved() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        Path restored = fixture.restorer(new ThrowAfterMoveOperations(MoveStage.SAFETY))
            .restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
                chain.v3.getId());

        assertArrayEquals(bytes("newest"), Files.readAllBytes(restored.resolve("level.dat")));
        assertArrayEquals(bytes("current"), Files.readAllBytes(fixture.gameDir
            .resolve("restore-safety").resolve("World1-20261009-010203-004")
            .resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void tamperedSafetyAfterCompletedMoveStopsRestoreAndPreservesVerifiedTemporary()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(new TamperAfterOriginalMoveOperations(
                OriginalMoveStage.SAFETY)).restore(fixture.gameDir, fixture.savesDir,
                    "World1", fixture.repo, chain.v3.getId()));

        Path recovery = fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id").toAbsolutePath().normalize();
        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertEquals(recovery.toString(), error.getArguments()[0]);
        assertArrayEquals(bytes("newest"), Files.readAllBytes(recovery.resolve("level.dat")));
        assertFalse(Files.exists(fixture.world, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(bytes("tampered"), Files.readAllBytes(fixture.gameDir
            .resolve("restore-safety").resolve("World1-20261009-010203-004")
            .resolve("current.dat")));
    }

    @Test
    public void runtimeAfterSafetyMovePreservesVerifiedTemporaryRecovery() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(new RuntimeAfterMoveOperations(MoveStage.SAFETY))
                .restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
                    chain.v3.getId()));

        Path recovery = fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id").toAbsolutePath().normalize();
        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertEquals(recovery.toString(), error.getArguments()[0]);
        assertArrayEquals(bytes("newest"), Files.readAllBytes(recovery.resolve("level.dat")));
    }

    @Test
    public void unsafeNameDuringOriginalAuthenticationFailsClosedAndPreservesTemporaryRecovery()
            throws Exception {
        try {
            Path probe = temp.newFolder("unsafe-name-probe").toPath()
                .resolve("unsafe\nname.dat");
            Files.write(probe, bytes("probe"));
            Files.delete(probe);
        } catch (IOException | UnsupportedOperationException | SecurityException
                 | IllegalArgumentException unavailable) {
            Assume.assumeNoException("Filesystem does not support the unsafe test name",
                unavailable);
        }

        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(new TamperAfterOriginalMoveOperations(
                OriginalMoveStage.SAFETY_WITH_UNSAFE_NAME)).restore(fixture.gameDir,
                    fixture.savesDir, "World1", fixture.repo, chain.v3.getId()));

        Path recovery = fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id").toAbsolutePath().normalize();
        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertEquals(recovery.toString(), error.getArguments()[0]);
        assertArrayEquals(bytes("newest"), Files.readAllBytes(recovery.resolve("level.dat")));
    }

    @Test
    public void promotionMoveThatCompletesBeforeThrowIsVerifiedAndAccepted() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        Path restored = fixture.restorer(new ThrowAfterMoveOperations(MoveStage.PROMOTION))
            .restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
                chain.v3.getId());

        assertArrayEquals(bytes("newest"), Files.readAllBytes(restored.resolve("level.dat")));
        assertArrayEquals(bytes("current"), Files.readAllBytes(fixture.gameDir
            .resolve("restore-safety").resolve("World1-20261009-010203-004")
            .resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void promotedWorldIsStrictlyReverifiedBeforeItIsAccepted() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        BackupRestorer.MoveOperations tamperingMove = new BackupRestorer.MoveOperations() {
            @Override
            public void move(Path source, Path target, CopyOption... options)
                    throws IOException {
                if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                    throw new AtomicMoveNotSupportedException(source.toString(),
                        target.toString(), "exercise fallback");
                }
                Files.move(source, target, options);
                if (source.toString().contains("worldbackup-tmp")) {
                    Files.write(target.resolve("level.dat"), bytes("tampered"));
                }
            }
        };

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(tamperingMove).restore(fixture.gameDir,
                fixture.savesDir, "World1", fixture.repo, chain.v3.getId()));

        assertEquals("worldbackup.error.restore_swap", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.savesDir.resolve("World1").resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("restore-safety")
            .resolve("World1-20261009-010203-004"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void ambiguousPromotionPreservesAndReportsExistingVerifiedRecovery()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        BackupRestorer.MoveOperations ambiguousMove = new BackupRestorer.MoveOperations() {
            @Override
            public void move(Path source, Path target, CopyOption... options)
                    throws IOException {
                if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                    throw new AtomicMoveNotSupportedException(source.toString(),
                        target.toString(), "exercise fallback");
                }
                if (source.toString().contains("worldbackup-tmp")) {
                    Files.createDirectory(target);
                    write(target.resolve("unknown.dat"), "unknown");
                    throw new IOException("injected ambiguous promotion");
                }
                Files.move(source, target, options);
            }
        };

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(ambiguousMove).restore(fixture.gameDir,
                fixture.savesDir, "World1", fixture.repo, chain.v3.getId()));

        Path recovery = fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id").toAbsolutePath().normalize();
        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertEquals(recovery.toString(), error.getArguments()[0]);
        assertTrue(Files.isDirectory(recovery, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(bytes("newest"), Files.readAllBytes(recovery.resolve("level.dat")));
        assertTrue(Files.isDirectory(fixture.gameDir.resolve("restore-safety")
            .resolve("World1-20261009-010203-004"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(fixture.savesDir.resolve("World1"),
            LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void invalidTemporaryRecoveryIsSkippedInFavorOfValidOriginalSafetyCopy()
            throws Exception {
        for (RecoveryDamage damage : Arrays.asList(RecoveryDamage.TEMP_DAMAGED,
                RecoveryDamage.TEMP_LINK, RecoveryDamage.TEMP_DELETED)) {
            Fixture fixture = fixture();
            Chain chain = fixture.chain(true);
            Path safety = fixture.gameDir.resolve("restore-safety")
                .resolve("World1-20261009-010203-004").toAbsolutePath().normalize();

            BackupException error = assertThrows(BackupException.class,
                () -> fixture.restorer(new RecoveryDamageOperations(fixture, damage))
                    .restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
                        chain.v3.getId()));

            assertEquals(damage.name(), "worldbackup.error.restore_rollback",
                error.getTranslationKey());
            assertEquals(damage.name(), safety.toString(), error.getArguments()[0]);
            assertArrayEquals(bytes("current"),
                Files.readAllBytes(safety.resolve("current.dat")));
        }
    }

    @Test
    public void invalidSafetyCopyIsNotReportedAsRecovery() throws Exception {
        for (RecoveryDamage damage : Arrays.asList(RecoveryDamage.SAFETY_LINK,
                RecoveryDamage.SAFETY_DAMAGED)) {
            Fixture fixture = fixture();
            Chain chain = fixture.chain(true);

            BackupException error = assertThrows(BackupException.class,
                () -> fixture.restorer(new RecoveryDamageOperations(fixture, damage))
                    .restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
                        chain.v3.getId()));

            assertEquals(damage.name(), "worldbackup.error.restore_rollback",
                error.getTranslationKey());
            assertEquals(damage.name(), 0, error.getArguments().length);
            assertNotNull(damage.name(), error.getCause());
            assertTrue(damage.name(),
                error.getCause().getMessage().contains("no recovery path"));
        }
    }

    @Test
    public void rollbackMoveThatCompletesBeforeThrowRestoresOriginalAndCleansTemp()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(new ThrowAfterMoveOperations(MoveStage.ROLLBACK))
                .restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
                    chain.v3.getId()));

        assertEquals("worldbackup.error.restore_swap", error.getTranslationKey());
        assertArrayEquals(bytes("current"), Files.readAllBytes(
            fixture.savesDir.resolve("World1").resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("restore-safety")
            .resolve("World1-20261009-010203-004"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void tamperedWorldAfterCompletedRollbackPreservesVerifiedTemporaryRecovery()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(new TamperAfterOriginalMoveOperations(
                OriginalMoveStage.ROLLBACK)).restore(fixture.gameDir, fixture.savesDir,
                    "World1", fixture.repo, chain.v3.getId()));

        Path recovery = fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id").toAbsolutePath().normalize();
        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertEquals(recovery.toString(), error.getArguments()[0]);
        assertArrayEquals(bytes("newest"), Files.readAllBytes(recovery.resolve("level.dat")));
        assertArrayEquals(bytes("tampered"),
            Files.readAllBytes(fixture.world.resolve("current.dat")));
        assertFalse(Files.exists(fixture.gameDir.resolve("restore-safety")
            .resolve("World1-20261009-010203-004"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void tamperedSafetyDuringPromotionIsRejectedBeforeSuccessfulReturn()
            throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer(new TamperAfterOriginalMoveOperations(
                OriginalMoveStage.SAFETY_DURING_PROMOTION)).restore(fixture.gameDir,
                    fixture.savesDir, "World1", fixture.repo, chain.v3.getId()));

        Path recovery = fixture.gameDir.resolve("worldbackup-tmp")
            .resolve("World1-fixed-id").toAbsolutePath().normalize();
        assertEquals("worldbackup.error.restore_rollback", error.getTranslationKey());
        assertEquals(recovery.toString(), error.getArguments()[0]);
        assertArrayEquals(bytes("newest"), Files.readAllBytes(recovery.resolve("level.dat")));
        assertArrayEquals(bytes("tampered"),
            Files.readAllBytes(fixture.world.resolve("current.dat")));
    }

    @Test
    public void rootSessionLockMayChangeDuringOriginalAuthentication() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);

        Path restored = fixture.restorer(new TamperAfterOriginalMoveOperations(
            OriginalMoveStage.SESSION_LOCK_DURING_PROMOTION)).restore(fixture.gameDir,
                fixture.savesDir, "World1", fixture.repo, chain.v3.getId());

        assertArrayEquals(bytes("newest"), Files.readAllBytes(restored.resolve("level.dat")));
        assertArrayEquals(bytes("live lock"), Files.readAllBytes(fixture.gameDir
            .resolve("restore-safety").resolve("World1-20261009-010203-004")
            .resolve("session.lock")));
    }

    @Test
    public void strictRestoreVerificationAcceptsNormalRegularFileTree() throws Exception {
        Path root = temp.newFolder("strict-normal").toPath();
        write(root.resolve("a.dat"), "same");
        write(root.resolve("nested/b.dat"), "other");
        write(root.resolve("session.lock"), "ignored at the world root");

        BackupRestorer.verifyRestoredTree(root,
            manifest("a.dat", "same", "nested/b.dat", "other"));
    }

    @Test
    public void strictRestoreVerificationRejectsInternalFileLinkEvenWithSameContents()
            throws Exception {
        Path root = temp.newFolder("strict-file-link").toPath();
        Path target = write(root.resolve("b.dat"), "same");
        try {
            Files.createSymbolicLink(root.resolve("a.dat"), target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        BackupException error = assertThrows(BackupException.class,
            () -> BackupRestorer.verifyRestoredTree(root,
                manifest("a.dat", "same", "b.dat", "same")));

        assertEquals("worldbackup.error.restore_verify", error.getTranslationKey());
    }

    @Test
    public void strictRestoreVerificationRejectsExtraFileLink() throws Exception {
        Path root = temp.newFolder("strict-extra-file-link").toPath();
        write(root.resolve("level.dat"), "level");
        Path outsideFile = write(temp.newFolder("link-target").toPath().resolve("extra.dat"),
            "extra");
        try {
            Files.createSymbolicLink(root.resolve("extra.dat"), outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        BackupException error = assertThrows(BackupException.class,
            () -> BackupRestorer.verifyRestoredTree(root,
                manifest("level.dat", "level")));

        assertEquals("worldbackup.error.restore_verify", error.getTranslationKey());
    }

    @Test
    public void strictRestoreVerificationRejectsExtraDirectoryLink() throws Exception {
        Path root = temp.newFolder("strict-extra-directory-link").toPath();
        write(root.resolve("level.dat"), "level");
        Path outsideDirectory = temp.newFolder("directory-target").toPath();
        try {
            Files.createSymbolicLink(root.resolve("linked-dir"), outsideDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links are unavailable", unavailable);
        }

        BackupException error = assertThrows(BackupException.class,
            () -> BackupRestorer.verifyRestoredTree(root,
                manifest("level.dat", "level")));

        assertEquals("worldbackup.error.restore_verify", error.getTranslationKey());
    }

    @Test
    public void strictRestoreVerificationRejectsExtraDirectory() throws Exception {
        Path root = temp.newFolder("strict-extra-directory").toPath();
        write(root.resolve("level.dat"), "level");
        Files.createDirectory(root.resolve("unexpected"));

        BackupException error = assertThrows(BackupException.class,
            () -> BackupRestorer.verifyRestoredTree(root,
                manifest("level.dat", "level")));

        assertEquals("worldbackup.error.restore_verify", error.getTranslationKey());
    }

    @Test
    public void safetyNameCollisionDoesNotOverwriteExistingCopy() throws Exception {
        Fixture fixture = fixture();
        Chain chain = fixture.chain(true);
        Path safetyRoot = Files.createDirectories(fixture.gameDir.resolve("restore-safety"));
        Path occupied = Files.createDirectories(safetyRoot.resolve("World1-20261009-010203-004"));
        write(occupied.resolve("keep.dat"), "keep");

        fixture.restorer().restore(fixture.gameDir, fixture.savesDir, "World1",
            fixture.repo, chain.v3.getId());

        assertArrayEquals(bytes("keep"), Files.readAllBytes(occupied.resolve("keep.dat")));
        assertArrayEquals(bytes("current"), Files.readAllBytes(safetyRoot
            .resolve("World1-20261009-010203-004-1").resolve("current.dat")));
    }

    @Test
    public void temporaryIdCollisionPreservesExistingDirectoryAndUsesAnotherId()
            throws Exception {
        Fixture fixture = fixture(false);
        Chain chain = fixture.chain(true);
        Path temporaryRoot = Files.createDirectories(
            fixture.gameDir.resolve("worldbackup-tmp"));
        Path occupied = Files.createDirectories(temporaryRoot.resolve("World1-fixed-id"));
        write(occupied.resolve("keep.dat"), "keep");
        List<String> ids = new ArrayList<>(Arrays.asList("fixed-id", "second-id"));
        BackupRestorer restorer = new BackupRestorer(new ArchiveStore(), new CatalogStore(),
            new InjectedMoves(InjectedMoves.Failure.NONE), () -> RESTORE_TIME,
            () -> ids.remove(0));

        restorer.restore(fixture.gameDir, fixture.savesDir, "World1", fixture.repo,
            chain.v1.getId());

        assertArrayEquals(bytes("keep"), Files.readAllBytes(occupied.resolve("keep.dat")));
        assertFalse(Files.exists(temporaryRoot.resolve("World1-second-id")));
    }

    @Test
    public void rejectsWorldTargetThatIsAFile() throws Exception {
        Fixture fixture = fixture(false);
        Chain chain = fixture.chain(true);
        write(fixture.savesDir.resolve("World1"), "not a directory");

        BackupException error = assertThrows(BackupException.class,
            () -> fixture.restorer().restore(fixture.gameDir, fixture.savesDir,
                "World1", fixture.repo, chain.v1.getId()));

        assertEquals("worldbackup.error.path_escape", error.getTranslationKey());
        assertArrayEquals(bytes("not a directory"),
            Files.readAllBytes(fixture.savesDir.resolve("World1")));
    }

    private Fixture fixture() throws Exception {
        return fixture(true);
    }

    private Fixture fixture(boolean existingWorld) throws Exception {
        Path root = temp.newFolder().toPath();
        Path gameDir = Files.createDirectories(root.resolve("game"));
        Path savesDir = Files.createDirectories(gameDir.resolve("saves"));
        Path world = savesDir.resolve("World1");
        if (existingWorld) {
            write(world.resolve("current.dat"), "current");
        }
        Path repo = Files.createDirectories(gameDir.resolve("backups").resolve("World1"));
        write(gameDir.resolve("outside.dat"), "outside");
        return new Fixture(gameDir, savesDir, world, repo);
    }

    private static BackupVersion version(String id, BackupMode mode, String archive,
                                         String parent, String base, boolean visible,
                                         SnapshotManifest manifest, List<String> changed,
                                         List<String> deleted) {
        return new BackupVersion(id, 1L, BackupTrigger.MANUAL, mode, archive, parent, base,
            visible, 0L, 0L, 0L, manifest, changed, deleted);
    }

    private static SnapshotManifest manifest(String... pathAndContents) throws Exception {
        SnapshotManifest result = new SnapshotManifest();
        for (int index = 0; index < pathAndContents.length; index += 2) {
            byte[] contents = bytes(pathAndContents[index + 1]);
            result.put(pathAndContents[index],
                new FileFingerprint(contents.length, 0L, sha256(contents)));
        }
        return result;
    }

    private static Path write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes(contents));
        return path;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] contents) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(contents);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private final class Fixture {
        private final Path gameDir;
        private final Path savesDir;
        private final Path world;
        private final Path repo;

        private Fixture(Path gameDir, Path savesDir, Path world, Path repo) {
            this.gameDir = gameDir;
            this.savesDir = savesDir;
            this.world = world;
            this.repo = repo;
        }

        private Chain chain(boolean visibleBase) throws Exception {
            SnapshotManifest v1Manifest = manifest("level.dat", "old", "deleted.dat", "gone");
            archive("v1.zip", v1Manifest, Arrays.asList("level.dat", "deleted.dat"),
                Collections.<String>emptyList());
            BackupVersion v1 = version("v1", BackupMode.FULL, "v1.zip", null, null,
                visibleBase, v1Manifest, Collections.<String>emptyList(),
                Collections.<String>emptyList());

            SnapshotManifest v2Manifest = manifest("level.dat", "new", "added.dat", "added");
            archive("v2.zip", v2Manifest, Arrays.asList("level.dat", "added.dat"),
                Collections.singletonList("deleted.dat"));
            BackupVersion v2 = version("v2", BackupMode.INCREMENTAL, "v2.zip", "v1", "v1",
                false, v2Manifest, Arrays.asList("level.dat", "added.dat"),
                Collections.singletonList("deleted.dat"));

            SnapshotManifest v3Manifest = manifest("level.dat", "newest", "added.dat", "added");
            archive("v3.zip", v3Manifest, Collections.singletonList("level.dat"),
                Collections.<String>emptyList());
            BackupVersion v3 = version("v3", BackupMode.INCREMENTAL, "v3.zip", "v2", "v1",
                true, v3Manifest, Collections.singletonList("level.dat"),
                Collections.<String>emptyList());
            saveCatalog(v1, v2, v3);
            return new Chain(v1, v2, v3);
        }

        private void archive(String name, SnapshotManifest snapshot, List<String> included,
                             List<String> deleted) throws Exception {
            Path source = Files.createDirectories(temp.newFolder().toPath().resolve("source"));
            for (String path : included) {
                FileFingerprint expected = snapshot.get(path);
                byte[] contents;
                if ("level.dat".equals(path)) {
                    contents = contentsMatching(expected, "old", "new", "newest", "future",
                        "restored", "value");
                } else if ("deleted.dat".equals(path)) {
                    contents = bytes("gone");
                } else if ("added.dat".equals(path)) {
                    contents = bytes("added");
                } else if ("session.lock".equals(path)) {
                    contents = bytes("lock");
                } else {
                    contents = bytes("restored");
                }
                Path file = write(source.resolve(path), new String(contents, StandardCharsets.UTF_8));
                Files.setLastModifiedTime(file,
                    java.nio.file.attribute.FileTime.fromMillis(expected.getModifiedTimeMillis()));
            }
            new ArchiveStore().write(repo, name, source, snapshot, included, deleted,
                new BackupCancellation());
        }

        private void saveCatalog(BackupVersion... versions) throws Exception {
            BackupCatalog catalog = BackupCatalog.empty("World1", "World One");
            for (BackupVersion version : versions) {
                catalog.add(version);
            }
            new CatalogStore().save(repo, catalog);
        }

        private BackupRestorer restorer() {
            return restorer(new InjectedMoves(InjectedMoves.Failure.NONE));
        }

        private BackupRestorer restorer(BackupRestorer.MoveOperations moves) {
            return new BackupRestorer(new ArchiveStore(), new CatalogStore(), moves,
                () -> RESTORE_TIME, () -> "fixed-id");
        }

        private byte[] contentsMatching(FileFingerprint expected, String... candidates)
                throws Exception {
            for (String candidate : candidates) {
                byte[] contents = bytes(candidate);
                if (contents.length == expected.getSize()
                        && sha256(contents).equals(expected.getSha256())) {
                    return contents;
                }
            }
            throw new AssertionError("No fixture contents match fingerprint");
        }
    }

    private static final class Chain {
        private final BackupVersion v1;
        private final BackupVersion v2;
        private final BackupVersion v3;

        private Chain(BackupVersion v1, BackupVersion v2, BackupVersion v3) {
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
        }
    }

    private static final class InjectedMoves implements BackupRestorer.MoveOperations {
        private enum Failure { NONE, SAFETY, PROMOTION, PROMOTION_AND_ROLLBACK }

        private final Failure failure;

        private InjectedMoves(Failure failure) {
            this.failure = failure;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            boolean atomic = Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE);
            if (atomic) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "exercise same-volume fallback");
            }
            boolean toSafety = target.toString().contains("restore-safety");
            boolean fromTemporary = source.toString().contains("worldbackup-tmp");
            boolean rollback = source.toString().contains("restore-safety")
                && target.getFileName().toString().equals("World1");
            if (failure == Failure.SAFETY && toSafety
                    || (failure == Failure.PROMOTION
                        || failure == Failure.PROMOTION_AND_ROLLBACK) && fromTemporary
                    || failure == Failure.PROMOTION_AND_ROLLBACK && rollback) {
                throw new IOException("injected " + failure);
            }
            Files.move(source, target, options);
        }
    }

    private enum MoveStage { SAFETY, PROMOTION, ROLLBACK }

    private enum OriginalMoveStage {
        SAFETY, SAFETY_WITH_UNSAFE_NAME, SAFETY_DURING_PROMOTION,
        SESSION_LOCK_DURING_PROMOTION, ROLLBACK
    }

    private static final class TamperAfterOriginalMoveOperations
            implements BackupRestorer.MoveOperations {
        private final OriginalMoveStage tamperAfter;
        private Path safety;

        private TamperAfterOriginalMoveOperations(OriginalMoveStage tamperAfter) {
            this.tamperAfter = tamperAfter;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "exercise fallback");
            }
            boolean promotion = source.toString().contains("worldbackup-tmp");
            boolean rollback = source.toString().contains("restore-safety");
            if (promotion && tamperAfter == OriginalMoveStage.ROLLBACK) {
                throw new IOException("injected promotion failure before move");
            }
            Files.move(source, target, options);
            if (!promotion && !rollback) {
                safety = target;
            }
            if ((tamperAfter == OriginalMoveStage.SAFETY && !promotion && !rollback)
                    || (tamperAfter == OriginalMoveStage.ROLLBACK && rollback)
                    || (tamperAfter == OriginalMoveStage.SAFETY_DURING_PROMOTION
                        && promotion)) {
                Path tamperTarget = tamperAfter == OriginalMoveStage.SAFETY_DURING_PROMOTION
                    ? safety : target;
                Files.write(tamperTarget.resolve("current.dat"), bytes("tampered"));
            } else if (tamperAfter == OriginalMoveStage.SAFETY_WITH_UNSAFE_NAME
                    && !promotion && !rollback) {
                Files.write(target.resolve("current.dat"), bytes("tampered"));
                Files.write(target.resolve("unsafe\nname.dat"), bytes("unsafe"));
            } else if (tamperAfter == OriginalMoveStage.SESSION_LOCK_DURING_PROMOTION
                    && promotion) {
                Files.write(safety.resolve("session.lock"), bytes("live lock"));
            }
        }
    }

    private static final class RuntimeAfterMoveOperations
            implements BackupRestorer.MoveOperations {
        private final MoveStage throwAfter;

        private RuntimeAfterMoveOperations(MoveStage throwAfter) {
            this.throwAfter = throwAfter;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "exercise fallback");
            }
            Files.move(source, target, options);
            boolean safety = !source.toString().contains("worldbackup-tmp")
                && !source.toString().contains("restore-safety");
            if (throwAfter == MoveStage.SAFETY && safety) {
                throw new IllegalStateException("injected runtime after safety move");
            }
        }
    }

    private enum RecoveryDamage {
        TEMP_DAMAGED, TEMP_LINK, TEMP_DELETED, SAFETY_LINK, SAFETY_DAMAGED
    }

    private static final class RecoveryDamageOperations
            implements BackupRestorer.MoveOperations {
        private final Fixture fixture;
        private final RecoveryDamage damage;

        private RecoveryDamageOperations(Fixture fixture, RecoveryDamage damage) {
            this.fixture = fixture;
            this.damage = damage;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "exercise fallback");
            }
            boolean promotion = source.toString().contains("worldbackup-tmp");
            boolean rollback = source.toString().contains("restore-safety");
            if (promotion) {
                Files.createDirectory(target);
                write(target.resolve("unknown.dat"), "unknown");
                damageRecoverySource(source);
                throw new IOException("injected ambiguous promotion with " + damage);
            }
            if (rollback && damage == RecoveryDamage.TEMP_DELETED) {
                throw new IOException("injected rollback failure");
            }
            Files.move(source, target, options);
        }

        private void damageRecoverySource(Path temporary) throws IOException {
            if (damage == RecoveryDamage.TEMP_DAMAGED
                    || damage == RecoveryDamage.SAFETY_LINK
                    || damage == RecoveryDamage.SAFETY_DAMAGED) {
                Files.write(temporary.resolve("level.dat"), bytes("damaged"));
            } else {
                Files.delete(temporary.resolve("level.dat"));
                Files.delete(temporary.resolve("added.dat"));
                Files.delete(temporary);
                if (damage == RecoveryDamage.TEMP_LINK) {
                    Path target = Files.createDirectory(fixture.gameDir.resolve(
                        "invalid-temp-link-target"));
                    createSymbolicLinkOrSkip(temporary, target);
                }
            }
            if (damage == RecoveryDamage.SAFETY_LINK) {
                Path safety = fixture.gameDir.resolve("restore-safety")
                    .resolve("World1-20261009-010203-004");
                Files.delete(safety.resolve("current.dat"));
                Files.delete(safety);
                Path target = Files.createDirectory(fixture.gameDir.resolve(
                    "invalid-safety-link-target"));
                createSymbolicLinkOrSkip(safety, target);
            } else if (damage == RecoveryDamage.SAFETY_DAMAGED) {
                Path safety = fixture.gameDir.resolve("restore-safety")
                    .resolve("World1-20261009-010203-004");
                Files.write(safety.resolve("current.dat"), bytes("damaged"));
            }
        }

        private static void createSymbolicLinkOrSkip(Path link, Path target)
                throws IOException {
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | SecurityException unavailable) {
                Assume.assumeNoException("Symbolic links are unavailable", unavailable);
            } catch (IOException unavailable) {
                Assume.assumeNoException("Symbolic links are unavailable", unavailable);
            }
        }
    }

    private static final class ThrowAfterMoveOperations
            implements BackupRestorer.MoveOperations {
        private final MoveStage throwAfter;

        private ThrowAfterMoveOperations(MoveStage throwAfter) {
            this.throwAfter = throwAfter;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "exercise fallback");
            }
            MoveStage stage = source.toString().contains("worldbackup-tmp")
                ? MoveStage.PROMOTION
                : source.toString().contains("restore-safety")
                    ? MoveStage.ROLLBACK : MoveStage.SAFETY;
            if (throwAfter == MoveStage.ROLLBACK && stage == MoveStage.PROMOTION) {
                throw new IOException("injected promotion failure before move");
            }
            Files.move(source, target, options);
            if (stage == throwAfter) {
                throw new IOException("injected " + stage + " failure after move");
            }
        }
    }
}
