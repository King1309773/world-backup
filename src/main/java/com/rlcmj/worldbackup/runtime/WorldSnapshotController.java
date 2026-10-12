package com.rlcmj.worldbackup.runtime;

import com.rlcmj.worldbackup.core.BackupCancellation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ISaveHandler;

import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldSnapshotController {
    private final SnapshotStager stager;

    public WorldSnapshotController() {
        this(java.nio.file.Paths.get(".").toAbsolutePath().normalize());
    }

    WorldSnapshotController(Path gameDirectory) {
        this(new NioSnapshotStager(gameDirectory.resolve(".worldbackup-staging")));
    }

    WorldSnapshotController(SnapshotStager stager) {
        this.stager = Objects.requireNonNull(stager, "stager");
    }

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
        Prepared prepared = prepareInitialized(server);
        try {
            return stage(prepared, new BackupCancellation());
        } catch (Exception failure) {
            try {
                releaseWorlds(prepared);
            } catch (Exception restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    public Prepared prepare(MinecraftServer server) throws Exception {
        MinecraftSnapshotServerFactory factory = new MinecraftSnapshotServerFactory(server);
        factory.requireServerThread();
        return prepareInitialized(factory.create());
    }

    private Prepared prepareInitialized(SnapshotServer server) throws Exception {
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
            Path sourceWorld = server.worldDirectory();
            return new Prepared(server, sourceWorld, states);
        } catch (Exception failure) {
            restoreChangedFlags(states, failure);
            throw failure;
        }
    }

    public Handle stage(Prepared prepared, BackupCancellation cancellation)
            throws Exception {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(cancellation, "cancellation").check();
        StagedSnapshot staged = stager.stage(prepared.sourceWorldDirectory, cancellation);
        try {
            cancellation.check();
            return new Handle(prepared, staged);
        } catch (Exception failure) {
            try {
                staged.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public void unfreeze(Handle handle) throws Exception {
        Objects.requireNonNull(handle, "handle");
        handle.prepared.server.requireServerThread();
        Exception failure = null;
        try {
            restoreWorlds(handle.prepared);
        } catch (Exception restoreFailure) {
            failure = restoreFailure;
        }
        try {
            cleanup(handle);
        } catch (Exception cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        if (failure != null) throw failure;
    }

    public void releaseWorlds(Handle handle) throws Exception {
        Objects.requireNonNull(handle, "handle");
        handle.prepared.server.requireServerThread();
        restoreWorlds(handle.prepared);
    }

    public void releaseWorlds(Prepared prepared) throws Exception {
        Objects.requireNonNull(prepared, "prepared");
        prepared.server.requireServerThread();
        restoreWorlds(prepared);
    }

    public void cleanup(Handle handle) throws Exception {
        Objects.requireNonNull(handle, "handle");
        synchronized (handle) {
            if (handle.snapshotClosed) return;
            handle.staged.close();
            handle.snapshotClosed = true;
        }
    }

    private static void restoreWorlds(Prepared prepared) throws Exception {
        synchronized (prepared) {
            if (prepared.worldsReleased) return;
            restoreWorldFlagsAndSave(prepared);
            prepared.worldsReleased = true;
        }
    }

    private static void restoreWorldFlagsAndSave(Prepared handle) throws Exception {
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
        private final Prepared prepared;
        private final StagedSnapshot staged;
        private boolean snapshotClosed;

        private Handle(Prepared prepared, StagedSnapshot staged) {
            this.prepared = Objects.requireNonNull(prepared, "prepared");
            this.staged = Objects.requireNonNull(staged, "staged");
        }

        public Path getWorldDirectory() {
            return staged.root().toAbsolutePath().normalize();
        }

        public Path getSourceWorldDirectory() {
            return prepared.sourceWorldDirectory;
        }
    }

    public static final class Prepared {
        private final SnapshotServer server;
        private final Path sourceWorldDirectory;
        private final List<WorldState> states;
        private boolean worldsReleased;

        private Prepared(SnapshotServer server, Path sourceWorldDirectory,
                         List<WorldState> states) {
            this.server = Objects.requireNonNull(server, "server");
            this.sourceWorldDirectory = Objects.requireNonNull(sourceWorldDirectory,
                "sourceWorldDirectory").toAbsolutePath().normalize();
            this.states = new ArrayList<>(states);
        }
    }

    interface SnapshotStager {
        StagedSnapshot stage(Path source) throws Exception;
        default StagedSnapshot stage(Path source, BackupCancellation cancellation)
                throws Exception {
            cancellation.check();
            StagedSnapshot result = stage(source);
            cancellation.check();
            return result;
        }
    }

    interface StagedSnapshot extends AutoCloseable {
        Path root();
        @Override void close() throws Exception;
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

    static final class NioSnapshotStager implements SnapshotStager {
        private static final long MINIMUM_HEADROOM = 64L * 1024L * 1024L;
        private final Path stagingBase;
        private final SpaceProbe spaceProbe;
        private final FileCopier copier;

        NioSnapshotStager(Path stagingBase) {
            this(stagingBase, store -> store.getUsableSpace(),
                new FileCopier() {
                    @Override public void copy(Path source, Path target)
                            throws IOException {
                        copyFile(source, target, new BackupCancellation());
                    }

                    @Override public void copy(Path source, Path target,
                                               BackupCancellation cancellation)
                            throws IOException {
                        copyFile(source, target, cancellation);
                    }
                });
        }

        NioSnapshotStager(Path stagingBase, SpaceProbe spaceProbe, FileCopier copier) {
            this.stagingBase = Objects.requireNonNull(stagingBase, "stagingBase")
                .toAbsolutePath().normalize();
            this.spaceProbe = Objects.requireNonNull(spaceProbe, "spaceProbe");
            this.copier = Objects.requireNonNull(copier, "copier");
        }

        @Override public StagedSnapshot stage(Path source) throws IOException {
            return stage(source, new BackupCancellation());
        }

        @Override public StagedSnapshot stage(Path source,
                                              BackupCancellation cancellation)
                throws IOException {
            Objects.requireNonNull(cancellation, "cancellation").check();
            Path absolute = Objects.requireNonNull(source, "source")
                .toAbsolutePath().normalize();
            if (stagingBase.startsWith(absolute) || absolute.startsWith(stagingBase)) {
                throw new IOException("Snapshot staging directory overlaps active world");
            }
            Path stagingParent = Objects.requireNonNull(stagingBase.getParent(),
                "stagingBase parent");
            Files.createDirectories(stagingParent);
            Path resolvedParent = stagingParent.toRealPath();
            Files.createDirectories(stagingBase);
            Path resolvedBase = stagingBase.toRealPath();
            if (!resolvedBase.startsWith(resolvedParent)) {
                throw new IOException("Snapshot staging directory escapes game directory");
            }
            Path resolvedSource = absolute.toRealPath();
            if (resolvedBase.startsWith(resolvedSource)
                    || resolvedSource.startsWith(resolvedBase)) {
                throw new IOException("Snapshot staging directory overlaps active world");
            }
            sweepOrphans(resolvedBase, cancellation);
            SnapshotPlan plan = inspectTree(resolvedSource, cancellation);
            long bytes = plan.totalBytes;
            long headroom = Math.max(MINIMUM_HEADROOM, bytes / 20L);
            long required = saturatedAdd(bytes, headroom);
            if (spaceProbe.usableSpace(Files.getFileStore(resolvedBase)) < required) {
                throw new IOException("Insufficient space for world snapshot");
            }
            Path staged = Files.createTempDirectory(resolvedBase, "snapshot-")
                .toAbsolutePath().normalize();
            try {
                copyTree(resolvedSource, staged, plan, copier, cancellation);
                cancellation.check();
                return new NioStagedSnapshot(staged);
            } catch (IOException | RuntimeException failure) {
                try {
                    deleteTree(staged);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private static SnapshotPlan inspectTree(Path source,
                                                BackupCancellation cancellation)
                throws IOException {
            AtomicLong total = new AtomicLong();
            List<SnapshotEntry> directories = new ArrayList<>();
            List<SnapshotEntry> files = new ArrayList<>();
            walkTree(source, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory,
                        BasicFileAttributes attributes) throws IOException {
                    cancellation.check();
                    requireDirectory(source, directory, attributes);
                    if (!directory.equals(source)) {
                        directories.add(SnapshotEntry.capture(source, directory,
                            attributes));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attributes) throws IOException {
                    cancellation.check();
                    requireRegularFile(source, file, attributes);
                    if (source.relativize(file).toString().equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }
                    long current = total.get();
                    if (Long.MAX_VALUE - current < attributes.size()) {
                        throw new IOException("World snapshot size overflow");
                    }
                    total.set(current + attributes.size());
                    files.add(SnapshotEntry.capture(source, file, attributes));
                    return FileVisitResult.CONTINUE;
                }
            });
            Comparator<SnapshotEntry> byPortablePath = Comparator.comparing(
                entry -> entry.relative.toString().replace('\\', '/'));
            directories.sort(byPortablePath);
            files.sort(byPortablePath);
            return new SnapshotPlan(directories, files, total.get());
        }

        private static void copyTree(Path source, Path target, SnapshotPlan plan,
                                     FileCopier copier,
                                     BackupCancellation cancellation)
                throws IOException {
            for (SnapshotEntry entry : plan.directories) {
                cancellation.check();
                Path directory = source.resolve(entry.relative);
                entry.verifyDirectory(source, directory);
                Files.createDirectory(target.resolve(entry.relative));
            }
            for (SnapshotEntry entry : plan.files) {
                cancellation.check();
                Path file = source.resolve(entry.relative);
                entry.verifyFile(source, file);
                Path destination = target.resolve(entry.relative);
                copier.copy(file, destination, cancellation);
                cancellation.check();
                entry.verifyFile(source, file);
                if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(destination) != entry.size) {
                    throw new IOException("Incomplete world snapshot copy: " + file);
                }
            }
        }

        private static void copyFile(Path source, Path target,
                                     BackupCancellation cancellation)
                throws IOException {
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
                 OutputStream output = Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    cancellation.check();
                    output.write(buffer, 0, count);
                }
            }
            Files.setLastModifiedTime(target, Files.getLastModifiedTime(source,
                LinkOption.NOFOLLOW_LINKS));
        }

        private static void sweepOrphans(Path base, BackupCancellation cancellation)
                throws IOException {
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(base,
                    "snapshot-*")) {
                for (Path entry : entries) {
                    cancellation.check();
                    BasicFileAttributes attributes = Files.readAttributes(entry,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    Path followed = entry.toRealPath();
                    Path notFollowed = entry.toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory() || attributes.isSymbolicLink()
                            || !followed.equals(notFollowed)
                            || !followed.startsWith(base)) {
                        continue;
                    }
                    deleteTree(entry);
                }
            }
        }

        private static void walkTree(Path source, SimpleFileVisitor<Path> visitor)
                throws IOException {
            Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE, visitor);
        }

        private static void requireDirectory(Path source, Path path,
                                             BasicFileAttributes attributes)
                throws IOException {
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Unsupported world snapshot directory: " + path);
            }
            requireContainedRealPath(source, path);
        }

        private static void requireRegularFile(Path source, Path path,
                                               BasicFileAttributes attributes)
                throws IOException {
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("Unsupported world snapshot entry: " + path);
            }
            requireContainedRealPath(source, path);
        }

        private static void requireContainedRealPath(Path source, Path path)
                throws IOException {
            Path followed = path.toRealPath();
            Path notFollowed = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!followed.startsWith(source) || !followed.equals(notFollowed)) {
                throw new IOException("World snapshot entry escapes source: " + path);
            }
        }

        private static long saturatedAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }

        private static final class SnapshotPlan {
            private final List<SnapshotEntry> directories;
            private final List<SnapshotEntry> files;
            private final long totalBytes;

            private SnapshotPlan(List<SnapshotEntry> directories,
                                 List<SnapshotEntry> files, long totalBytes) {
                this.directories = directories;
                this.files = files;
                this.totalBytes = totalBytes;
            }
        }

        private static final class SnapshotEntry {
            private final Path relative;
            private final Object fileKey;
            private final long size;
            private final long modifiedMillis;

            private SnapshotEntry(Path relative, Object fileKey, long size,
                                  long modifiedMillis) {
                this.relative = relative;
                this.fileKey = fileKey;
                this.size = size;
                this.modifiedMillis = modifiedMillis;
            }

            private static SnapshotEntry capture(Path source, Path path,
                                                 BasicFileAttributes attributes) {
                return new SnapshotEntry(source.relativize(path), attributes.fileKey(),
                    attributes.size(), attributes.lastModifiedTime().toMillis());
            }

            private void verifyDirectory(Path source, Path path) throws IOException {
                BasicFileAttributes current = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                requireDirectory(source, path, current);
                verifyFileKey(path, current);
            }

            private void verifyFile(Path source, Path path) throws IOException {
                BasicFileAttributes current = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                requireRegularFile(source, path, current);
                verifyFileKey(path, current);
                if (size != current.size()
                        || modifiedMillis != current.lastModifiedTime().toMillis()) {
                    throw new IOException("World changed while staging snapshot: " + path);
                }
            }

            private void verifyFileKey(Path path, BasicFileAttributes current)
                    throws IOException {
                Object currentKey = current.fileKey();
                boolean keyChanged = fileKey != null && currentKey != null
                    && !fileKey.equals(currentKey);
                if (keyChanged) {
                    throw new IOException("World changed while staging snapshot: " + path);
                }
            }
        }
    }

    interface SpaceProbe { long usableSpace(FileStore store) throws IOException; }
    interface FileCopier {
        void copy(Path source, Path target) throws IOException;
        default void copy(Path source, Path target, BackupCancellation cancellation)
                throws IOException {
            cancellation.check();
            copy(source, target);
            cancellation.check();
        }
    }

    private static final class NioStagedSnapshot implements StagedSnapshot {
        private final Path root;

        private NioStagedSnapshot(Path root) { this.root = root; }
        @Override public Path root() { return root; }
        @Override public void close() throws IOException { deleteTree(root); }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory,
                    IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
