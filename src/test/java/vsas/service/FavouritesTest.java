package vsas.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.model.User;
import vsas.model.UserType;

import static org.junit.jupiter.api.Assertions.*;

class FavouritesTest {
    @TempDir Path temporary;
    private final User alice = user("1", "alice", UserType.GENERAL);
    private final User bob = user("2", "bob", UserType.GENERAL);

    private static User user(String id, String username, UserType type) {
        return new User(id, username, "private-password-hash", "Test User",
                "test@example.com", "0412345678", type);
    }

    @Test void addRemoveAndRepeatedRequestsUseExplicitDesiredState() throws Exception {
        Favourites favourites = new Favourites();
        assertTrue(favourites.list(alice).isEmpty());
        favourites.set(alice, "scroll-1", false);
        favourites.set(alice, "scroll-1", true);
        favourites.set(alice, "scroll-1", true);
        assertTrue(favourites.contains(alice, "scroll-1"));
        assertEquals(Set.of("scroll-1"), favourites.list(alice));
        favourites.set(alice, "scroll-1", false);
        favourites.set(alice, "scroll-1", false);
        assertFalse(favourites.contains(alice, "scroll-1"));
        assertTrue(favourites.list(alice).isEmpty());
    }

    @Test void listReturnsAnImmutableIndependentSnapshot() throws Exception {
        Favourites favourites = new Favourites();
        favourites.set(alice, "scroll-1", true);
        Set<String> snapshot = favourites.list(alice);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("scroll-2"));
        favourites.set(alice, "scroll-2", true);
        assertEquals(Set.of("scroll-1"), snapshot);
    }

    @Test void persistenceSurvivesRestartAndContainsNoAccountSecrets() throws Exception {
        Path file = temporary.resolve("nested/favourites.db");
        Favourites favourites = new Favourites(file);
        favourites.set(alice, "scroll-1", true);
        favourites.set(alice, "scroll-2", true);
        favourites.set(bob, "scroll-3", true);
        favourites.set(alice, "scroll-2", false);

        Favourites restored = new Favourites(file);
        assertEquals(Set.of("scroll-1"), restored.list(alice));
        assertEquals(Set.of("scroll-3"), restored.list(bob));
        assertEquals(2, Files.readAllLines(file).size());
        for (String line : Files.readAllLines(file)) {
            String[] fields = line.split("\t");
            assertEquals(3, fields.length);
            for (String field : fields) {
                String decoded = new String(Base64.getUrlDecoder().decode(field), StandardCharsets.UTF_8);
                assertNotEquals(alice.passwordHash(), decoded);
                assertNotEquals(alice.email(), decoded);
            }
        }
    }

    @Test void accountsAreIsolatedEvenWhenAnIdIsReused() throws Exception {
        Path file = temporary.resolve("favourites.db");
        Favourites favourites = new Favourites(file);
        User reusedId = user(alice.idKey(), "replacement", UserType.GENERAL);
        User sameUsernameDifferentId = user("new-id", alice.username(), UserType.GENERAL);
        favourites.set(alice, "alice-scroll", true);
        favourites.set(bob, "bob-scroll", true);
        assertTrue(favourites.list(reusedId).isEmpty());
        assertTrue(favourites.list(sameUsernameDifferentId).isEmpty());
        favourites.set(reusedId, "new-scroll", true);
        favourites.set(reusedId, "alice-scroll", false);

        Favourites restored = new Favourites(file);
        assertEquals(Set.of("alice-scroll"), restored.list(alice));
        assertEquals(Set.of("new-scroll"), restored.list(reusedId));
        assertEquals(Set.of("bob-scroll"), restored.list(bob));
    }

    @Test void accountDeletionPersistsAndDoesNotDeleteAnotherOwnersCollection() throws Exception {
        Path file = temporary.resolve("favourites.db");
        Favourites favourites = new Favourites(file);
        favourites.set(alice, "scroll", true);
        favourites.set(bob, "scroll", true);
        favourites.removeAccount(alice);
        favourites.removeAccount(alice);
        Favourites restored = new Favourites(file);
        assertTrue(restored.list(alice).isEmpty());
        assertEquals(Set.of("scroll"), restored.list(bob));
        restored.removeAccount(bob);
        assertTrue(new Favourites(file).list(bob).isEmpty());
    }

    @Test void guestsAndUnauthenticatedCallersCannotUseAnyOperation() {
        Favourites favourites = new Favourites();
        User guest = user("guest", "guest", UserType.GUEST);
        User noRole = user("invalid", "invalid", null);
        for (User actor : new User[] {null, guest, noRole}) {
            assertThrows(SecurityException.class, () -> favourites.list(actor));
            assertThrows(SecurityException.class, () -> favourites.contains(actor, "scroll"));
            assertThrows(SecurityException.class, () -> favourites.set(actor, "scroll", true));
            assertThrows(SecurityException.class, () -> favourites.removeAccount(actor));
        }
    }

    @Test void administratorCanMaintainTheirOwnFavourites() throws Exception {
        Favourites favourites = new Favourites();
        User admin = user("admin", "admin", UserType.ADMIN);
        favourites.set(admin, "scroll", true);
        assertTrue(favourites.contains(admin, "scroll"));
        assertTrue(favourites.list(alice).isEmpty());
    }

    @Test void blankScrollOrAccountIdentifiersAreRejected() {
        Favourites favourites = new Favourites();
        for (String id : new String[] {null, "", "  "}) {
            assertThrows(IllegalArgumentException.class, () -> favourites.set(alice, id, true));
            assertThrows(IllegalArgumentException.class, () -> favourites.contains(alice, id));
            assertThrows(IllegalArgumentException.class,
                    () -> favourites.list(user(id, "user", UserType.GENERAL)));
            assertThrows(IllegalArgumentException.class,
                    () -> favourites.list(user("id", id, UserType.GENERAL)));
        }
    }

    @Test void storageIsDeterministicAndHandlesEncodedUnicodeAndSeparators() throws Exception {
        Path first = temporary.resolve("first.db");
        Path second = temporary.resolve("second.db");
        Favourites a = new Favourites(first);
        Favourites b = new Favourites(second);
        User unicode = user("用户\t1", "Name\n姓名", UserType.GENERAL);
        a.set(bob, "z", true);
        a.set(alice, "b", true);
        a.set(alice, "a", true);
        a.set(unicode, "卷轴\t\n1", true);
        b.set(unicode, "卷轴\t\n1", true);
        b.set(alice, "a", true);
        b.set(alice, "b", true);
        b.set(bob, "z", true);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(Set.of("卷轴\t\n1"), new Favourites(first).list(unicode));
        try (var children = Files.list(temporary)) {
            assertEquals(2, children.count(), "Atomic replacement should leave no temporary files");
        }
    }

    @Test void malformedStorageDoesNotSilentlyResetCollections() throws Exception {
        Path file = temporary.resolve("bad.db");
        String valid = encoded("id") + "\t" + encoded("username") + "\t" + encoded("scroll");
        for (String data : new String[] {
                "missing-fields", "a\tb\tc\td", "!!!\tYQ\tYg", "\tYQ\tYg",
                encoded(" ") + "\tYQ\tYg", "_w\tYQ\tYg", "\n", valid + "\n" + valid
        }) {
            Files.writeString(file, data);
            assertThrows(IOException.class, () -> new Favourites(file), data);
        }
        assertThrows(IOException.class, () -> new Favourites(temporary));
    }

    @Test void failedAddAndRemoveKeepTheLastAcknowledgedState() throws Exception {
        Path file = temporary.resolve("favourites.db");
        Favourites favourites = new Favourites(file);
        favourites.set(alice, "saved", true);
        Path backup = temporary.resolve("backup.db");
        Files.move(file, backup);
        Files.createDirectory(file);
        Files.writeString(file.resolve("block-replacement"), "block");

        assertThrows(IOException.class, () -> favourites.set(alice, "new", true));
        assertThrows(IOException.class, () -> favourites.set(alice, "saved", false));
        assertThrows(IOException.class, () -> favourites.removeAccount(alice));
        assertEquals(Set.of("saved"), favourites.list(alice));
        assertEquals(Set.of("saved"), new Favourites(backup).list(alice));
        assertDoesNotThrow(() -> favourites.set(alice, "saved", true));
        assertDoesNotThrow(() -> favourites.set(alice, "absent", false));
        assertDoesNotThrow(() -> favourites.removeAccount(bob));
        try (var children = Files.list(temporary)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
