package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class SafetyCopyStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void listsAndDeletesOnlyDirectCopiesForSelectedWorld() throws Exception {
        Path game = temp.newFolder("game").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        Path first = Files.createDirectory(root.resolve("World1-20261009-010203-004"));
        Files.write(first.resolve("level.dat"), new byte[] { 1 });
        Files.createDirectory(root.resolve("Other-20261009-010203-004"));

        SafetyCopyStore store = new SafetyCopyStore();
        List<Path> copies = store.list(game, "World1");
        assertEquals(1, copies.size());
        assertEquals(first, copies.get(0));

        store.delete(game, "World1", first.getFileName().toString());
        assertFalse(Files.exists(first));
    }

    @Test
    public void rejectsTraversalAndSymbolicLinkCopies() throws Exception {
        Path game = temp.newFolder("game-links").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        SafetyCopyStore store = new SafetyCopyStore();

        assertThrows(BackupException.class,
            () -> store.delete(game, "World1", "../outside"));

        Path outside = Files.createDirectory(game.resolve("outside"));
        Path link = root.resolve("World1-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            org.junit.Assume.assumeNoException(failure);
        }
        assertThrows(BackupException.class,
            () -> store.delete(game, "World1", link.getFileName().toString()));
    }
}
