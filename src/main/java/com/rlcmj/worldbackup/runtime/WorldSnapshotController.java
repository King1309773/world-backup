package com.rlcmj.worldbackup.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ISaveHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorldSnapshotController {
    public Handle freeze(MinecraftServer server) throws Exception {
        return freeze(new MinecraftSnapshotServerFactory(server));
    }

    Handle freeze(SnapshotServerFactory factory) throws Exception {
        Objects.requireNonNull(factory, "factory").requireServerThread();
        return freezeInitialized(Objects.requireNonNull(factory.create(), "snapshotServer"));
    }

    Handle freeze(SnapshotServer server) throws Exception {
        Objects.requireNonNull(server, "server").requireServerThread();
        return freezeInitialized(server);
    }

    private Handle freezeInitialized(SnapshotServer server) throws Exception {
        List<SnapshotWorld> worlds = new ArrayList<>(server.worlds());
        server.saveAllWorlds();
        server.saveAllPlayers();
        for (SnapshotWorld world : worlds) {
            world.saveAllChunks();
        }
        Set<SnapshotSaveHandler> handlers = Collections.newSetFromMap(
            new IdentityHashMap<SnapshotSaveHandler, Boolean>());
        for (SnapshotWorld world : worlds) {
            SnapshotSaveHandler handler = world.saveHandler();
            if (handlers.add(handler)) {
                handler.flush();
            }
        }

        List<WorldState> states = new ArrayList<>();
        try {
            for (SnapshotWorld world : worlds) {
                WorldState state = new WorldState(world, world.isSavingDisabled());
                states.add(state);
                world.setSavingDisabled(true);
            }
            return new Handle(server, server.worldDirectory(), states);
        } catch (Exception failure) {
            restoreChangedFlags(states, failure);
            throw failure;
        }
    }

    public void unfreeze(Handle handle) throws Exception {
        Objects.requireNonNull(handle, "handle");
        handle.server.requireServerThread();
        Exception failure = null;
        for (WorldState state : handle.states) {
            try {
                state.world.setSavingDisabled(state.originalDisabled);
            } catch (Exception restoreFailure) {
                if (failure == null) failure = restoreFailure;
                else failure.addSuppressed(restoreFailure);
            }
        }
        if (handle.server.isRunning()) {
            try {
                handle.server.saveAllWorlds();
            } catch (Exception saveFailure) {
                if (failure == null) failure = saveFailure;
                else failure.addSuppressed(saveFailure);
            }
        }
        if (failure != null) throw failure;
    }

    public void emergencyUnfreeze(Handle handle) throws Exception {
        Objects.requireNonNull(handle, "handle");
        Exception failure = null;
        for (WorldState state : handle.states) {
            try {
                state.world.setSavingDisabled(state.originalDisabled);
            } catch (Exception restoreFailure) {
                if (failure == null) failure = restoreFailure;
                else failure.addSuppressed(restoreFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private static void restoreChangedFlags(List<WorldState> states, Throwable primary) {
        for (WorldState state : states) {
            try {
                state.world.setSavingDisabled(state.originalDisabled);
            } catch (Exception restoreFailure) {
                primary.addSuppressed(restoreFailure);
            }
        }
    }

    public static final class Handle {
        private final SnapshotServer server;
        private final Path worldDirectory;
        private final List<WorldState> states;

        private Handle(SnapshotServer server, Path worldDirectory, List<WorldState> states) {
            this.server = server;
            this.worldDirectory = Objects.requireNonNull(worldDirectory, "worldDirectory")
                .toAbsolutePath().normalize();
            this.states = new ArrayList<>(states);
        }

        public Path getWorldDirectory() {
            return worldDirectory;
        }
    }

    interface SnapshotServer {
        void requireServerThread();
        void saveAllWorlds() throws Exception;
        void saveAllPlayers() throws Exception;
        List<SnapshotWorld> worlds();
        Path worldDirectory();
        boolean isRunning();
    }

    interface SnapshotServerFactory {
        void requireServerThread();
        SnapshotServer create();
    }

    interface SnapshotWorld {
        void saveAllChunks() throws Exception;
        SnapshotSaveHandler saveHandler();
        boolean isSavingDisabled();
        void setSavingDisabled(boolean disabled) throws Exception;
    }

    interface SnapshotSaveHandler {
        void flush() throws Exception;
    }

    private static final class WorldState {
        private final SnapshotWorld world;
        private final boolean originalDisabled;

        private WorldState(SnapshotWorld world, boolean originalDisabled) {
            this.world = world;
            this.originalDisabled = originalDisabled;
        }
    }

    private static final class MinecraftSnapshotServer implements SnapshotServer {
        private final MinecraftServer server;
        private final List<SnapshotWorld> worlds;

        private MinecraftSnapshotServer(MinecraftServer server) {
            this.server = Objects.requireNonNull(server, "server");
            Map<ISaveHandler, SnapshotSaveHandler> handlers = new IdentityHashMap<>();
            this.worlds = new ArrayList<>();
            if (server.worlds != null) {
                for (WorldServer world : server.worlds) {
                    if (world == null) continue;
                    ISaveHandler raw = world.getSaveHandler();
                    SnapshotSaveHandler handler = handlers.get(raw);
                    if (handler == null) {
                        handler = raw::flush;
                        handlers.put(raw, handler);
                    }
                    worlds.add(new MinecraftSnapshotWorld(world, handler));
                }
            }
        }

        @Override public void requireServerThread() {
            requireMinecraftServerThread(server);
        }
        @Override public void saveAllWorlds() { server.saveAllWorlds(false); }
        @Override public void saveAllPlayers() { server.getPlayerList().saveAllPlayerData(); }
        @Override public List<SnapshotWorld> worlds() { return worlds; }
        @Override public Path worldDirectory() {
            if (server.worlds == null || server.worlds.length == 0 || server.worlds[0] == null) {
                throw new IllegalStateException("No loaded world");
            }
            return server.worlds[0].getSaveHandler().getWorldDirectory().toPath();
        }
        @Override public boolean isRunning() {
            return server.isServerRunning() && !server.isServerStopped();
        }
    }

    private static final class MinecraftSnapshotServerFactory
            implements SnapshotServerFactory {
        private final MinecraftServer server;

        private MinecraftSnapshotServerFactory(MinecraftServer server) {
            this.server = Objects.requireNonNull(server, "server");
        }

        @Override public void requireServerThread() {
            requireMinecraftServerThread(server);
        }

        @Override public SnapshotServer create() {
            return new MinecraftSnapshotServer(server);
        }
    }

    private static void requireMinecraftServerThread(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException(
                "World snapshot operation must run on server thread");
        }
    }

    private static final class MinecraftSnapshotWorld implements SnapshotWorld {
        private final WorldServer world;
        private final SnapshotSaveHandler handler;

        private MinecraftSnapshotWorld(WorldServer world, SnapshotSaveHandler handler) {
            this.world = world;
            this.handler = handler;
        }

        @Override public void saveAllChunks() throws Exception {
            world.saveAllChunks(true, null);
        }
        @Override public SnapshotSaveHandler saveHandler() { return handler; }
        @Override public boolean isSavingDisabled() { return world.disableLevelSaving; }
        @Override public void setSavingDisabled(boolean disabled) {
            world.disableLevelSaving = disabled;
        }
    }
}
