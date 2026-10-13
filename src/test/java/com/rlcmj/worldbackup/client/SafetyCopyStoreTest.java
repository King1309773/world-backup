package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

    @Test
    public void rejectsPrefixLookalikesButDeletesExactRestorerNames() throws Exception {
        Path game = temp.newFolder("game-names").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        Path fake = Files.createDirectory(root.resolve("World1-arbitrary"));
        Files.write(fake.resolve("keep.dat"), new byte[] { 1 });
        Path impossibleDate = Files.createDirectory(
            root.resolve("World1-20261340-256161-999"));
        Files.write(impossibleDate.resolve("keep.dat"), new byte[] { 3 });
        Path exact = Files.createDirectory(root.resolve("World1-20261009-010203-004-999"));
        Files.write(exact.resolve("level.dat"), new byte[] { 2 });
        SafetyCopyStore store = new SafetyCopyStore();

        assertThrows(BackupException.class,
            () -> store.delete(game, "World1", fake.getFileName().toString()));
        assertTrue(Files.exists(fake.resolve("keep.dat")));
        assertThrows(BackupException.class, () -> store.delete(game, "World1",
            impossibleDate.getFileName().toString()));
        assertTrue(Files.exists(impossibleDate.resolve("keep.dat")));

        store.delete(game, "World1", exact.getFileName().toString());
        assertFalse(Files.exists(exact));
    }

    @Test
    public void quotesRegexCharactersInWorldId() throws Exception {
        Path game = temp.newFolder("game-regex-name").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        Path exact = Files.createDirectory(root.resolve("World[1]-20261009-010203-004"));
        Files.write(exact.resolve("level.dat"), new byte[] { 1 });

        new SafetyCopyStore().delete(game, "World[1]",
            exact.getFileName().toString());

        assertFalse(Files.exists(exact));
    }

    @Test
    public void validationFailureLateInTreeDeletesNothingAndLeavesExternalCanary()
            throws Exception {
        Path game = temp.newFolder("game-atomic-validation").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        Path copy = Files.createDirectory(root.resolve("World1-20261009-010203-004"));
        Path ordinary = Files.write(copy.resolve("a-ordinary.dat"), new byte[] { 1 });
        Path nested = Files.createDirectory(copy.resolve("z-nested"));
        Path outside = temp.newFolder("outside-canary").toPath();
        Path canary = Files.write(outside.resolve("canary.dat"), new byte[] { 7 });
        Path link = nested.resolve("z-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            org.junit.Assume.assumeNoException(failure);
        }

        assertThrows(BackupException.class, () -> new SafetyCopyStore().delete(
            game, "World1", copy.getFileName().toString()));

        assertTrue(Files.exists(ordinary));
        assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(canary));
        assertTrue(Files.exists(copy));
    }

    @Test
    public void rejectsWindowsJunctionBeforeDeletingAnything() throws Exception {
        org.junit.Assume.assumeTrue(System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win"));
        Path game = temp.newFolder("game-junction").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        Path copy = Files.createDirectory(root.resolve("World1-20261009-010203-004"));
        Path ordinary = Files.write(copy.resolve("a-ordinary.dat"), new byte[] { 1 });
        Path internalTarget = Files.createDirectory(copy.resolve("junction-target"));
        Path canary = Files.write(internalTarget.resolve("canary.dat"), new byte[] { 7 });
        Path junction = copy.resolve("z-junction");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
            junction.toString(), internalTarget.toString()).redirectErrorStream(true).start();
        int exit = process.waitFor();
        org.junit.Assume.assumeTrue("mklink /J unavailable", exit == 0
            && Files.exists(junction, LinkOption.NOFOLLOW_LINKS));

        assertThrows(BackupException.class, () -> new SafetyCopyStore().delete(
            game, "World1", copy.getFileName().toString()));

        assertTrue(Files.exists(ordinary));
        assertTrue(Files.exists(junction, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(canary));
        assertTrue(Files.exists(copy));
    }

    @Test
    public void injectedWindowsReparseFlagRejectsTreeBeforeDeletingAnything()
            throws Exception {
        Path game = temp.newFolder("game-injected-reparse").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        Path copy = Files.createDirectory(root.resolve("World1-20261009-010203-004"));
        Path ordinary = Files.write(copy.resolve("a-ordinary.dat"), new byte[] { 1 });
        Path reparse = Files.write(copy.resolve("z-reparse.dat"), new byte[] { 2 });

        SafetyCopyStore store = new SafetyCopyStore(
            path -> path.equals(reparse) ? 0x400 : 0);

        assertThrows(BackupException.class, () -> store.delete(
            game, "World1", copy.getFileName().toString()));
        assertTrue(Files.exists(ordinary));
        assertTrue(Files.exists(reparse));
        assertTrue(Files.exists(copy));
    }

    @Test
    public void unsupportedDosAttributesDoNotRejectOrdinaryTree() throws Exception {
        Path game = temp.newFolder("game-no-dos-view").toPath();
        Path root = Files.createDirectories(game.resolve("restore-safety"));
        Path copy = Files.createDirectory(root.resolve("World1-20261009-010203-004"));
        Files.write(copy.resolve("level.dat"), new byte[] { 1 });
        SafetyCopyStore store = new SafetyCopyStore(path -> {
            throw new UnsupportedOperationException("no DOS view");
        });

        store.delete(game, "World1", copy.getFileName().toString());

        assertFalse(Files.exists(copy));
    }
}
