package com.rlcmj.worldbackup.runtime;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorldSnapshotControllerTest {
    @Test
    public void freezesInSaveFlushFlagOrderAndFlushesSharedHandlerOnce() throws Exception {
        List<String> calls = new ArrayList<>();
        FakeSave shared = new FakeSave("shared", calls);
        FakeSave other = new FakeSave("other", calls);
        FakeWorld first = new FakeWorld("one", false, shared, calls);
        FakeWorld second = new FakeWorld("two", true, shared, calls);
        FakeWorld third = new FakeWorld("three", false, other, calls);
        FakeServer server = new FakeServer(calls, first, second, third);

        WorldSnapshotController controller = new WorldSnapshotController();
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

        assertThrows(Exception.class, () -> new WorldSnapshotController().freeze(server));

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
            () -> new WorldSnapshotController().freeze(server));

        assertFalse(first.disabled);
        assertTrue(second.disabled);
        assertEquals(Arrays.asList("flag-one-false", "flag-two-true"),
            calls.subList(calls.size() - 2, calls.size()));
    }

    private static final class FakeServer implements WorldSnapshotController.SnapshotServer {
        private final List<String> calls;
        private final List<WorldSnapshotController.SnapshotWorld> worlds;
        private boolean failWorldDirectory;

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
            return Paths.get("world");
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
        }
    }
}
