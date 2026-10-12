package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.core.BackupCancellation;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Assume;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorldSnapshotControllerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void workerReadsStagedPlayerDataNotLaterLiveSaves() throws Exception {
        Path game = temporaryFolder.newFolder("game-live-change").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Path playerData = Files.createDirectories(world.resolve("playerdata"))
            .resolve("player.dat");
        Files.write(playerData, Arrays.asList("freeze-point"), StandardCharsets.UTF_8);
        List<String> calls = new ArrayList<>();
        FakeWorld fakeWorld = new FakeWorld("one", false,
            new FakeSave("save", calls), calls);
        FakeServer server = new FakeServer(calls, fakeWorld);
        server.worldDirectory = world;
        WorldSnapshotController controller = new WorldSnapshotController(game);

        WorldSnapshotController.Handle handle = controller.freeze(server);
        Path staged = handle.getWorldDirectory();
        Files.write(playerData, Arrays.asList("later-periodic-save"),
            StandardCharsets.UTF_8);

        assertEquals("freeze-point", Files.readAllLines(handle.getWorldDirectory()
            .resolve("playerdata/player.dat"), StandardCharsets.UTF_8).get(0));
        assertEquals(world.toAbsolutePath().normalize(),
            handle.getSourceWorldDirectory());
        controller.unfreeze(handle);
        assertFalse(Files.exists(staged));
    }

    @Test
    public void realNioStagingUsesControlledRootAndCleansOnClose() throws Exception {
        Path game = temporaryFolder.newFolder("game-normal").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), Arrays.asList("level"),
            StandardCharsets.UTF_8);
        Path stagingBase = game.resolve(".worldbackup-staging");
        WorldSnapshotController.StagedSnapshot staged =
            new WorldSnapshotController.NioSnapshotStager(stagingBase).stage(world);

        Path stagedRoot = staged.root();
        assertTrue(stagedRoot.startsWith(stagingBase.toRealPath()));
        assertTrue(Files.exists(stagedRoot.resolve("level.dat")));
        staged.close();

        assertFalse(Files.exists(stagedRoot));
        assertDirectoryEmpty(stagingBase);
    }

    @Test
    public void realNioStagingRefusesBaseInsideWorldWithoutCreatingIt() throws Exception {
        Path game = temporaryFolder.newFolder("game-overlap").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), new byte[] {1});
        Path stagingBase = world.resolve("staging-must-not-exist");

        assertThrows(IOException.class,
            () -> new WorldSnapshotController.NioSnapshotStager(stagingBase).stage(world));

        assertFalse(Files.exists(stagingBase, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void realNioStagingRejectsEscapingSymlink() throws Exception {
        Path game = temporaryFolder.newFolder("game-link").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Path outside = temporaryFolder.newFile("outside.dat").toPath();
        try {
            Files.createSymbolicLink(world.resolve("escape.dat"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assume.assumeNoException("Symbolic links unavailable", unavailable);
        }
        Path stagingBase = game.resolve(".worldbackup-staging");

        assertThrows(IOException.class,
            () -> new WorldSnapshotController.NioSnapshotStager(stagingBase).stage(world));
        assertDirectoryEmpty(stagingBase);
    }

    @Test
    public void realNioStagingRejectsEscapingWindowsJunctionWithoutCopyingTarget()
            throws Exception {
        Assume.assumeTrue(System.getProperty("os.name", "").toLowerCase()
            .contains("windows"));
        Path game = temporaryFolder.newFolder("game-junction").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Path outside = temporaryFolder.newFolder("junction-target").toPath();
        Path secret = outside.resolve("must-not-copy.dat");
        Files.write(secret, new byte[] {7});
        Path junction = world.resolve("escape-dir");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
            junction.toString(), outside.toString()).redirectErrorStream(true).start();
        try (java.io.InputStream output = process.getInputStream()) {
            while (output.read() != -1) { }
        }
        Assume.assumeTrue("Junctions unavailable", process.waitFor() == 0);
        Path stagingBase = game.resolve(".worldbackup-staging");
        try {
            assertThrows(IOException.class,
                () -> new WorldSnapshotController.NioSnapshotStager(stagingBase)
                    .stage(world));
            assertDirectoryEmpty(stagingBase);
            assertEquals(7, Files.readAllBytes(secret)[0]);
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    public void realNioStagingPreflightsSpaceBeforeCreatingSnapshot() throws Exception {
        Path game = temporaryFolder.newFolder("game-space").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), new byte[] {1});
        Path stagingBase = game.resolve(".worldbackup-staging");
        WorldSnapshotController.NioSnapshotStager stager =
            new WorldSnapshotController.NioSnapshotStager(stagingBase,
                store -> 0L, Files::copy);

        assertThrows(IOException.class, () -> stager.stage(world));
        assertDirectoryEmpty(stagingBase);
    }

    @Test
    public void realNioStagingDeletesPartialSnapshotAfterCopyFailure() throws Exception {
        Path game = temporaryFolder.newFolder("game-copy-failure").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), new byte[] {1});
        Path stagingBase = game.resolve(".worldbackup-staging");
        WorldSnapshotController.NioSnapshotStager stager =
            new WorldSnapshotController.NioSnapshotStager(stagingBase,
                store -> Long.MAX_VALUE, (source, target) -> {
                    Files.copy(source, target);
                    throw new IOException("copy failed");
                });

        assertThrows(IOException.class, () -> stager.stage(world));
        assertDirectoryEmpty(stagingBase);
    }

    @Test
    public void realNioStagingChecksCancellationBetweenCopiedFiles() throws Exception {
        Path game = temporaryFolder.newFolder("game-copy-cancel").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("a.dat"), new byte[] {1});
        Files.write(world.resolve("b.dat"), new byte[] {2});
        Path stagingBase = game.resolve(".worldbackup-staging");
        BackupCancellation cancellation = new BackupCancellation();
        AtomicInteger copies = new AtomicInteger();
        WorldSnapshotController.NioSnapshotStager stager =
            new WorldSnapshotController.NioSnapshotStager(stagingBase,
                store -> Long.MAX_VALUE, (source, target) -> {
                    Files.copy(source, target);
                    if (copies.incrementAndGet() == 1) cancellation.cancel();
                });

        assertThrows(java.io.InterruptedIOException.class,
            () -> stager.stage(world, cancellation));

        assertEquals(1, copies.get());
        assertDirectoryEmpty(stagingBase);
    }

    @Test
    public void orphanSweepDeletesOnlyControlledRealDirectories() throws Exception {
        Path game = temporaryFolder.newFolder("game-orphan-sweep").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), new byte[] {1});
        Path stagingBase = Files.createDirectories(game.resolve(".worldbackup-staging"));
        Path orphan = Files.createDirectories(stagingBase.resolve("snapshot-orphan"));
        Files.write(orphan.resolve("partial.dat"), new byte[] {2});
        Path unrelated = Files.createDirectories(stagingBase.resolve("manual-orphan"));
        Path matchingFile = Files.write(stagingBase.resolve("snapshot-not-a-directory"),
            new byte[] {4});
        Path outside = temporaryFolder.newFolder("outside-orphan-canary").toPath();
        Path canary = Files.write(outside.resolve("canary.dat"), new byte[] {3});
        Path link = stagingBase.resolve("snapshot-outside-link");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), outside.toString()).redirectErrorStream(true).start();
            Assume.assumeTrue("Junctions are unavailable",
                process.waitFor() == 0 && Files.exists(link));
        } else {
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                Assume.assumeNoException("Symbolic links are unavailable", unavailable);
            }
        }

        WorldSnapshotController.StagedSnapshot staged =
            new WorldSnapshotController.NioSnapshotStager(stagingBase).stage(world);
        staged.close();

        assertFalse(Files.exists(orphan));
        assertTrue(Files.isDirectory(unrelated));
        assertTrue(Files.isRegularFile(matchingFile));
        assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(canary));
        assertTrue(Files.exists(world.resolve("level.dat")));
        Files.delete(link);
    }

    @Test
    public void realNioStagingNeverCopiesSessionLock() throws Exception {
        Path game = temporaryFolder.newFolder("game-session-lock").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), new byte[] {1});
        Files.write(world.resolve("session.lock"), new byte[] {2});
        Path stagingBase = game.resolve(".worldbackup-staging");

        WorldSnapshotController.StagedSnapshot staged =
            new WorldSnapshotController.NioSnapshotStager(stagingBase).stage(world);
        try {
            assertTrue(Files.exists(staged.root().resolve("level.dat")));
            assertFalse(Files.exists(staged.root().resolve("session.lock")));
        } finally {
            staged.close();
        }
    }

    @Test
    public void controllerRollsBackFlagsAndPartialStageWhenCopyFails() throws Exception {
        Path game = temporaryFolder.newFolder("game-controller-copy-failure").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), new byte[] {1});
        Path stagingBase = game.resolve(".worldbackup-staging");
        WorldSnapshotController controller = new WorldSnapshotController(
            new WorldSnapshotController.NioSnapshotStager(stagingBase,
                store -> Long.MAX_VALUE, (source, target) -> {
                    Files.copy(source, target);
                    throw new IOException("copy failed");
                }));
        List<String> calls = new ArrayList<>();
        FakeWorld fakeWorld = new FakeWorld("one", false,
            new FakeSave("save", calls), calls);
        FakeServer server = new FakeServer(calls, fakeWorld);
        server.worldDirectory = world;

        assertThrows(IOException.class, () -> controller.freeze(server));

        assertFalse(fakeWorld.disabled);
        assertDirectoryEmpty(stagingBase);
    }

    @Test
    public void releasedWorldKeepsRealStageUntilWorkerCleanup() throws Exception {
        Path game = temporaryFolder.newFolder("game-deferred-cleanup").toPath();
        Path world = Files.createDirectories(game.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), new byte[] {1});
        List<String> calls = new ArrayList<>();
        FakeWorld fakeWorld = new FakeWorld("one", false,
            new FakeSave("save", calls), calls);
        FakeServer server = new FakeServer(calls, fakeWorld);
        server.worldDirectory = world;
        WorldSnapshotController controller = new WorldSnapshotController(game);

        WorldSnapshotController.Handle handle = controller.freeze(server);
        Path staged = handle.getWorldDirectory();
        controller.releaseWorlds(handle);

        assertFalse(fakeWorld.disabled);
        assertTrue(Files.exists(staged.resolve("level.dat")));
        controller.cleanup(handle);
        assertFalse(Files.exists(staged));
    }

    @Test
    public void rejectsOffThreadBeforeCreatingSnapshotAdapter() {
        List<String> calls = new ArrayList<>();
        WorldSnapshotController.SnapshotServerFactory factory =
            new WorldSnapshotController.SnapshotServerFactory() {
                @Override public void requireServerThread() {
                    calls.add("thread");
                    throw new IllegalStateException("off thread");
                }

                @Override public WorldSnapshotController.SnapshotServer create() {
                    calls.add("create-adapter");
                    throw new AssertionError("adapter must not be created off thread");
                }
            };

        assertThrows(IllegalStateException.class,
            () -> new WorldSnapshotController().freeze(factory));

        assertEquals(Arrays.asList("thread"), calls);
    }

    @Test
    public void freezesInSaveFlushFlagOrderAndFlushesSharedHandlerOnce() throws Exception {
        List<String> calls = new ArrayList<>();
        FakeSave shared = new FakeSave("shared", calls);
        FakeSave other = new FakeSave("other", calls);
        FakeWorld first = new FakeWorld("one", false, shared, calls);
        FakeWorld second = new FakeWorld("two", true, shared, calls);
        FakeWorld third = new FakeWorld("three", false, other, calls);
        FakeServer server = new FakeServer(calls, first, second, third);

        WorldSnapshotController controller = controllerWithoutCopy();
        WorldSnapshotController.Handle handle = controller.freeze(server);

        assertEquals(Paths.get("world").toAbsolutePath().normalize(),
            handle.getWorldDirectory());
        assertEquals(Arrays.asList("thread", "save-worlds", "save-players",
            "chunks-one", "chunks-two", "chunks-three", "flush-shared",
            "flush-other", "flag-one-true", "flag-two-true", "flag-three-true"), calls);
        assertTrue(first.disabled);
        assertTrue(second.disabled);
        assertTrue(third.disabled);

        controller.unfreeze(handle);

        assertFalse(first.disabled);
        assertTrue(second.disabled);
        assertFalse(third.disabled);
        assertEquals("save-worlds", calls.get(calls.size() - 1));
    }

    @Test
    public void restoresFlagsAlreadyChangedWhenFreezeFails() {
        List<String> calls = new ArrayList<>();
        FakeWorld first = new FakeWorld("one", false, new FakeSave("a", calls), calls);
        FakeWorld second = new FakeWorld("two", false, new FakeSave("b", calls), calls);
        second.failWhenSetting = true;
        FakeServer server = new FakeServer(calls, first, second);

        assertThrows(Exception.class, () -> controllerWithoutCopy().freeze(server));

        assertFalse(first.disabled);
        assertFalse(second.disabled);
        assertTrue(calls.contains("flag-one-false"));
    }

    @Test
    public void restoresEveryChangedFlagWhenWorldDirectoryLookupFails() {
        List<String> calls = new ArrayList<>();
        FakeWorld first = new FakeWorld("one", false, new FakeSave("a", calls), calls);
        FakeWorld second = new FakeWorld("two", true, new FakeSave("b", calls), calls);
        FakeServer server = new FakeServer(calls, first, second);
        server.failWorldDirectory = true;

        assertThrows(IllegalStateException.class,
            () -> controllerWithoutCopy().freeze(server));

        assertFalse(first.disabled);
        assertTrue(second.disabled);
        assertEquals(Arrays.asList("flag-one-false", "flag-two-true"),
            calls.subList(calls.size() - 2, calls.size()));
    }

    @Test
    public void interruptedFlagPreparationRestoresChangedFlagsBeforeStaging() {
        List<String> calls = new ArrayList<>();
        FakeWorld first = new FakeWorld("one", false, new FakeSave("a", calls), calls);
        FakeWorld second = new FakeWorld("two", false, new FakeSave("b", calls), calls);
        first.interruptAfterSetting = true;
        FakeServer server = new FakeServer(calls, first, second);
        AtomicInteger stagingCalls = new AtomicInteger();
        WorldSnapshotController controller = new WorldSnapshotController(source -> {
            stagingCalls.incrementAndGet();
            throw new AssertionError("cancelled preparation reached staging");
        });

        try {
            assertThrows(java.io.InterruptedIOException.class,
                () -> controller.freeze(server));
        } finally {
            Thread.interrupted();
        }

        assertFalse(first.disabled);
        assertFalse(second.disabled);
        assertEquals(0, stagingCalls.get());
        assertTrue(calls.contains("flag-one-false"));
    }

    private static WorldSnapshotController controllerWithoutCopy() {
        return new WorldSnapshotController(source -> new WorldSnapshotController.StagedSnapshot() {
            @Override public java.nio.file.Path root() { return source; }
            @Override public void close() { }
        });
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            assertFalse(entries.iterator().hasNext());
        }
    }

    private static final class FakeServer implements WorldSnapshotController.SnapshotServer {
        private final List<String> calls;
        private final List<WorldSnapshotController.SnapshotWorld> worlds;
        private boolean failWorldDirectory;
        private java.nio.file.Path worldDirectory = Paths.get("world");

        private FakeServer(List<String> calls, FakeWorld... worlds) {
            this.calls = calls;
            this.worlds = Arrays.asList(worlds);
        }

        @Override public void requireServerThread() { calls.add("thread"); }
        @Override public void saveAllWorlds() { calls.add("save-worlds"); }
        @Override public void saveAllPlayers() { calls.add("save-players"); }
        @Override public List<WorldSnapshotController.SnapshotWorld> worlds() { return worlds; }
        @Override public java.nio.file.Path worldDirectory() {
            if (failWorldDirectory) throw new IllegalStateException("directory failed");
            return worldDirectory;
        }
        @Override public boolean isRunning() { return true; }
    }

    private static final class FakeSave implements WorldSnapshotController.SnapshotSaveHandler {
        private final String name;
        private final List<String> calls;
        private FakeSave(String name, List<String> calls) { this.name = name; this.calls = calls; }
        @Override public void flush() { calls.add("flush-" + name); }
    }

    private static final class FakeWorld implements WorldSnapshotController.SnapshotWorld {
        private final String name;
        private final FakeSave save;
        private final List<String> calls;
        private boolean disabled;
        private boolean failWhenSetting;
        private boolean interruptAfterSetting;

        private FakeWorld(String name, boolean disabled, FakeSave save, List<String> calls) {
            this.name = name;
            this.disabled = disabled;
            this.save = save;
            this.calls = calls;
        }

        @Override public void saveAllChunks() { calls.add("chunks-" + name); }
        @Override public WorldSnapshotController.SnapshotSaveHandler saveHandler() { return save; }
        @Override public boolean isSavingDisabled() { return disabled; }
        @Override public void setSavingDisabled(boolean value) throws Exception {
            calls.add("flag-" + name + "-" + value);
            if (failWhenSetting && value) throw new Exception("set failed");
            disabled = value;
            if (interruptAfterSetting && value) Thread.currentThread().interrupt();
        }
    }
}
