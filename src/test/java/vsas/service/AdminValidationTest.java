package vsas.service;

import org.junit.jupiter.api.Test;
import vsas.model.User;
import vsas.model.UserType;
import vsas.repository.InMemoryUserRepository;
import vsas.repository.InMemoryScrollRepository;
import vsas.security.Pbkdf2PasswordHasher;
import static org.junit.jupiter.api.Assertions.*;

class AdminValidationTest {
    private final InMemoryUserRepository accounts = new InMemoryUserRepository();
    private final InMemoryScrollRepository files = new InMemoryScrollRepository();
    private final Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(10_000);
    private final LibraryAdminService service = new LibraryAdminService(accounts, hasher, files, new LibraryScrollService(files));
    private User admin() {
        service.initialiseAdmin("admin", "secret".toCharArray());
        return accounts.findByUsername("admin").orElseThrow();
    }
    private User add(User actor, String id, String username, char[] password, String name, String email, String phone) {
        return service.addUser(actor, id, username, password, name, email, phone, UserType.GENERAL);
    }

    @Test void rejectsInvalidProfileFieldsWithoutCreatingAccounts() {
        User actor = admin();
        String[] valid = {"unique", "username", "Name", "a@example.com", "0412345678"};
        for (int field = 1; field < valid.length; field++) {
            for (String bad : new String[]{null, "", " "}) {
                String[] values = valid.clone(); values[field] = bad;
                assertThrows(IllegalArgumentException.class, () -> add(actor, values[0], values[1],
                        "secret".toCharArray(), values[2], values[3], values[4]));
            }
        }
        for (String id : new String[]{"bad/id", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> add(actor, id, "new", "secret".toCharArray(), "Name", "a@example.com", "0412345678"));
        }
        assertThrows(IllegalArgumentException.class, () -> add(actor, "id", "new", null, "Name", "a@example.com", "0412345678"));
        assertThrows(IllegalArgumentException.class, () -> add(actor, "id", "new", new char[0], "Name", "a@example.com", "0412345678"));
        assertThrows(IllegalArgumentException.class, () -> add(actor, "id", "new", "secret".toCharArray(), "Name", "invalid", "0412345678"));
        for (String phone : new String[]{"abc", "123", "+61123"}) {
            assertThrows(IllegalArgumentException.class, () -> add(actor, "id", "new", "secret".toCharArray(), "Name", "a@example.com", phone));
        }
        assertEquals(1, accounts.findAll().size());
        User created = add(actor, "id", "new", "secret".toCharArray(), "Name", "a@example.com", "+61412345678");
        assertEquals("+61412345678", created.phone());
        assertThrows(IllegalArgumentException.class, () -> add(actor, "other-id", "new", "secret".toCharArray(), "Name", "a@example.com", "0412345678"));
    }

    @Test void handlesMissingGuestAndDeletedActorsAndAdministratorDeletion() {
        User actor = admin();
        User guest = new User("guest", "guest", "", "Guest", "", "", UserType.GUEST);
        assertThrows(SecurityException.class, () -> service.listUsers(null));
        assertThrows(SecurityException.class, () -> service.listUsers(guest));
        User second = service.addUser(actor, "second", "second", "secret".toCharArray(),
                "Second Admin", "s@example.com", "0412345678", UserType.ADMIN);
        for (String id : new String[]{null, "", " ", "missing"}) {
            assertThrows(IllegalArgumentException.class, () -> service.deleteUser(actor, id));
        }
        service.deleteUser(actor, second.idKey());
        assertTrue(accounts.findById(second.idKey()).isEmpty());
        assertThrows(ForbiddenException.class, () -> service.listUsers(second));
    }

    @Test void bootstrapDoesNotOverwriteAnExistingNonAdminUsername() {
        var existing = new User("existing", "taken", "hash", "Existing", "e@example.com", "0412345678", UserType.GENERAL);
        accounts.save(existing);
        service.initialiseAdmin("taken", "secret".toCharArray());
        assertEquals(existing, accounts.findByUsername("taken").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> service.initialiseAdmin(null, "secret".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> service.initialiseAdmin("other", null));
        service.initialiseAdmin("other", "secret".toCharArray());
        assertEquals(2, accounts.findAll().size());
    }
}
