package vsas.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import vsas.model.*;
import vsas.repository.UserRepository;
import vsas.security.PasswordHasher;
import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {
    private final Map<String, User> stored = new HashMap<>();
    private final UserRepository repository = new UserRepository() {
        public Optional<User> findById(String id) { return Optional.ofNullable(stored.get(id)); }
        public Optional<User> findByUsername(String name) {
            return stored.values().stream().filter(u -> u.username().equals(name)).findFirst();
        }
        public List<User> findAll() { return List.copyOf(stored.values()); }
        public void save(User user) { stored.put(user.idKey(), user); }
        public void delete(String id) { stored.remove(id); }
    };
    // Test double only; production must inject a salted password hasher.
    private final PasswordHasher hasher = new PasswordHasher() {
        public String hash(char[] password) { return "test-hash:" + Arrays.hashCode(password); }
        public boolean verify(char[] password, String hash) { return hash(password).equals(hash); }
    };
    private final UserService service = new UserService(repository, hasher);

    private User member(String id, String username, UserType type) {
        User user = new User(id, username, hasher.hash("old".toCharArray()),
                "Old Name", "old@example.com", "0412345678", type);
        repository.save(user);
        return user;
    }

    @Test void privateAccountDataRequiresTheCurrentStoredMember() {
        User actor = member("private", "reader", UserType.GENERAL);
        assertEquals(actor, service.requireCurrentMember(actor));
        assertThrows(SecurityException.class, () -> service.requireCurrentMember(null));
        User guest = new User("guest", "guest", "", "Guest", "", "", UserType.GUEST);
        assertThrows(SecurityException.class, () -> service.requireCurrentMember(guest));
        repository.delete(actor.idKey());
        assertThrows(SecurityException.class, () -> service.requireCurrentMember(actor));
        member(actor.idKey(), actor.username(), UserType.GUEST);
        assertThrows(SecurityException.class, () -> service.requireCurrentMember(actor));
        member(actor.idKey(), "replacement", UserType.GENERAL);
        assertThrows(SecurityException.class, () -> service.requireCurrentMember(actor));
        repository.save(new User(actor.idKey(), actor.username(), "new-salted-hash",
                actor.fullName(), actor.email(), actor.phone(), actor.type()));
        assertThrows(SecurityException.class, () -> service.requireCurrentMember(actor));
    }

    @Test void pictureUpdatesAreAuthenticatedAndPreservedByProfileChanges() throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        byte[] bytes = output.toByteArray();
        User actor = member("picture", "picture-user", UserType.GENERAL);
        assertThrows(SecurityException.class, () -> service.updatePicture(null, bytes));
        User guest = new User("guest", "guest", "", "Guest", "", "", UserType.GUEST);
        assertThrows(SecurityException.class, () -> service.updatePicture(guest, bytes));
        User missing = new User("missing", "missing", "", "Missing", "", "", UserType.GENERAL);
        assertThrows(SecurityException.class, () -> service.updatePicture(missing, bytes));
        User wrongId = new User("wrong-id", actor.username(), "", "", "", "", UserType.GENERAL);
        assertThrows(SecurityException.class, () -> service.updatePicture(wrongId, bytes));
        User updated = service.updatePicture(actor, bytes);
        assertTrue(updated.picture().startsWith("data:image/png;base64,"));
        User profile = service.updateProfile(updated, updated.idKey(), "New Name",
                "new@example.com", "0412345678", "new-password".toCharArray());
        assertEquals(updated.picture(), profile.picture());
        assertEquals(profile.picture(), service.login(actor.username(), "new-password".toCharArray()).picture());
    }

    @Test void recoveryRequiresAnAdminAndConsumesLinksOnce() {
        User admin = member("admin", "admin", UserType.ADMIN);
        User target = member("member", "member", UserType.GENERAL);
        assertThrows(SecurityException.class, () -> service.issuePasswordRecovery(null, "member"));
        assertThrows(SecurityException.class, () -> service.issuePasswordRecovery(target, "member"));
        assertThrows(IllegalArgumentException.class, () -> service.issuePasswordRecovery(admin, "missing"));
        String token = service.issuePasswordRecovery(admin, "member");
        assertEquals(43, token.length());
        User restored = service.recoverPassword(token, "new-secret".toCharArray());
        assertEquals(target.idKey(), restored.idKey());
        assertEquals(target.type(), restored.type());
        assertEquals(restored, service.login("member", "new-secret".toCharArray()));
        assertThrows(SecurityException.class, () -> service.login("member", "old".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> service.recoverPassword(token, "other".toCharArray()));
    }

    @Test void recoveryRejectsReplacedExpiredAndStaleAccountLinks() {
        User admin = member("admin", "admin", UserType.ADMIN);
        User target = member("member", "member", UserType.GENERAL);
        String old = service.issuePasswordRecovery(admin, "member");
        String fresh = service.issuePasswordRecovery(admin, "member");
        assertThrows(IllegalArgumentException.class, () -> service.recoverPassword(old, "new".toCharArray()));
        service.updateProfile(target, target.idKey(), target.fullName(), target.email(), target.phone(), "changed".toCharArray());
        assertThrows(IllegalArgumentException.class, () -> service.recoverPassword(fresh, "new".toCharArray()));
        String deleted = service.issuePasswordRecovery(admin, "member");
        repository.delete(target.idKey());
        assertThrows(IllegalArgumentException.class, () -> service.recoverPassword(deleted, "new".toCharArray()));
        member("member", "member", UserType.GENERAL);
        java.time.Instant issued = java.time.Instant.parse("2026-10-25T00:00:00Z");
        java.time.Clock clock = new java.time.Clock() {
            int calls;
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return calls++ < 1 ? issued : issued.plusSeconds(900); }
        };
        UserService expiring = new UserService(repository, hasher, () -> "unused", clock);
        String expired = expiring.issuePasswordRecovery(admin, "member");
        assertThrows(IllegalArgumentException.class, () -> expiring.recoverPassword(expired, "new".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> service.recoverPassword(null, "new".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> service.recoverPassword("bad", "new".toCharArray()));
    }

    @Test void recoveryRechecksStoredAdministratorAndTargetRoles() {
        User admin = member("admin", "admin", UserType.ADMIN);
        User target = member("member", "member", UserType.GENERAL);
        User impersonator = new User(admin.idKey(), "different-name", admin.passwordHash(),
                admin.fullName(), admin.email(), admin.phone(), UserType.ADMIN);
        assertThrows(SecurityException.class, () -> service.issuePasswordRecovery(impersonator, "member"));
        repository.delete(admin.idKey());
        assertThrows(SecurityException.class, () -> service.issuePasswordRecovery(admin, "member"));
        member("admin", "admin", UserType.ADMIN);
        String token = service.issuePasswordRecovery(admin, "member");
        member(target.idKey(), target.username(), UserType.GUEST);
        assertThrows(IllegalArgumentException.class, () -> service.recoverPassword(token, "new".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> service.issuePasswordRecovery(admin, "member"));
    }

    @Test void recoveryPrunesExpiredGrantsWithoutInvalidatingAnotherActiveAccount() {
        User admin = member("admin", "admin", UserType.ADMIN);
        member("first", "first", UserType.GENERAL);
        member("second", "second", UserType.GENERAL);
        member("third", "third", UserType.GENERAL);
        class MutableClock extends java.time.Clock {
            java.time.Instant now = java.time.Instant.parse("2026-10-25T00:00:00Z");
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return now; }
        }
        MutableClock clock = new MutableClock();
        UserService recovery = new UserService(repository, hasher, () -> "unused", clock);
        String expired = recovery.issuePasswordRecovery(admin, "first");
        clock.now = clock.now.plusSeconds(600);
        String active = recovery.issuePasswordRecovery(admin, "second");
        clock.now = clock.now.plusSeconds(300);
        recovery.issuePasswordRecovery(admin, "third");
        assertThrows(IllegalArgumentException.class, () -> recovery.recoverPassword(expired, "new".toCharArray()));
        assertEquals("second", recovery.recoverPassword(active, "new".toCharArray()).username());
    }

    @Test void unavailableRecoveryDigestFailsWithoutChangingThePassword() {
        User admin = member("admin", "admin", UserType.ADMIN);
        User target = member("member", "member", UserType.GENERAL);
        UserService unavailable = new UserService(repository, hasher, () -> "unused",
                java.time.Clock.systemUTC(), algorithm -> {
                    assertEquals("SHA-256", algorithm);
                    throw new java.security.NoSuchAlgorithmException("Test unavailable provider");
                });
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> unavailable.issuePasswordRecovery(admin, "member"));
        assertInstanceOf(java.security.NoSuchAlgorithmException.class, error.getCause());
        assertEquals(target, stored.get(target.idKey()));
        assertEquals(target, service.login("member", "old".toCharArray()));
    }

    @Test void rejectsInternationalPhoneWithIncorrectDigitCount() {
        assertThrows(IllegalArgumentException.class, () -> service.register(null, "alice",
                "secret".toCharArray(), "Alice", "alice@example.com", "+61 412 345"));
        assertTrue(stored.isEmpty());
    }

    @Test void registersAValidatedMemberWithAHashedPassword() {
        User created = service.register("alice-01", "alice", "secret".toCharArray(),
                "Alice Example", "alice@example.com", "0412345678");
        assertEquals(UserType.GENERAL, created.type());
        assertEquals(created, stored.get("alice-01"));
        assertNotEquals("secret", created.passwordHash());
        assertTrue(hasher.verify("secret".toCharArray(), created.passwordHash()));
    }

    @Test void registrationGeneratesAUniqueUserIdWhenNoneIsRequested() {
        User created = service.register(null, "alice", "secret".toCharArray(),
                "Alice Example", "alice@example.com", "0412345678");
        assertEquals("USR-00000001", created.idKey());
        assertEquals(created, stored.get(created.idKey()));
    }

    @Test void generatedIdsIncreaseAndContinueFromExistingAccounts() {
        member("USR-00000009", "existing", UserType.GENERAL);
        member("USR-ABC12345", "legacy", UserType.GENERAL);
        User first = service.register(null, "first", "secret".toCharArray(),
                "First", "first@example.com", "0412345678");
        assertEquals("USR-00000010", first.idKey());
        UserService restarted = new UserService(repository, hasher);
        User second = restarted.register(null, "second", "secret".toCharArray(),
                "Second", "second@example.com", "0412345678");
        assertEquals("USR-00000011", second.idKey());
    }

    @Test void generatedUserIdsRetryCollisionsAndEventuallyFailWithoutWriting() {
        member("USR-COLLIDE", "existing", UserType.GENERAL);
        Deque<String> candidates = new ArrayDeque<>(List.of("USR-COLLIDE", "USR-UNIQUE01"));
        UserService retrying = new UserService(repository, hasher, candidates::removeFirst);
        User created = retrying.register(" ", "alice", "secret".toCharArray(),
                "Alice Example", "alice@example.com", "0412345678");
        assertEquals("USR-UNIQUE01", created.idKey());

        UserService exhausted = new UserService(repository, hasher, () -> "USR-COLLIDE");
        assertThrows(IllegalStateException.class, () -> exhausted.register(
                null, "bob", "secret".toCharArray(),
                "Bob Example", "bob@example.com", "0498765432"));
        assertEquals(Set.of("USR-COLLIDE", "USR-UNIQUE01"), stored.keySet());
    }

    @Test void registrationRejectsDuplicateIdsAndUsernamesWithoutOverwriting() {
        User first = service.register("id-one", "alice", "secret".toCharArray(),
                "Alice", "alice@example.com", "0412345678");
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "id-one", "bob", "secret".toCharArray(),
                "Bob", "bob@example.com", "0498765432"));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "id-two", "alice", "secret".toCharArray(),
                "Alice Two", "alice2@example.com", "0498765432"));
        assertEquals(Map.of("id-one", first), stored);
    }

    @Test void registrationRejectsInvalidAccountDetails() {
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "bad id!", "alice", "secret".toCharArray(),
                "Alice", "alice@example.com", "0412345678"));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "x".repeat(65), "alice", "secret".toCharArray(),
                "Alice", "alice@example.com", "0412345678"));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "id", " ", "secret".toCharArray(),
                "Alice", "alice@example.com", "0412345678"));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "id", "alice", new char[0],
                "Alice", "alice@example.com", "0412345678"));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "id", "alice", null,
                "Alice", "alice@example.com", "0412345678"));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                "id", "alice", "secret".toCharArray(),
                "Alice", "alice@example.com", "123456789"));
        assertTrue(stored.isEmpty());
    }

    @Test void loginAuthenticatesOnlyValidRegisteredCredentials() {
        User created = service.register("id", "alice", "secret".toCharArray(),
                "Alice", "alice@example.com", "0412345678");
        assertEquals(created, service.login("alice", "secret".toCharArray()));
        assertThrows(SecurityException.class, () -> service.login("alice", "wrong".toCharArray()));
        assertThrows(SecurityException.class, () -> service.login("missing", "secret".toCharArray()));
        assertThrows(SecurityException.class, () -> service.login(null, "secret".toCharArray()));
        assertThrows(SecurityException.class, () -> service.login(" ", "secret".toCharArray()));
        assertThrows(SecurityException.class, () -> service.login("alice", null));
        assertThrows(SecurityException.class, () -> service.login("alice", new char[0]));
    }

    @Test void loginDoesNotAuthenticateGuestRecords() {
        member("guest-id", "guest", UserType.GUEST);
        assertThrows(SecurityException.class, () -> service.login("guest", "old".toCharArray()));
    }

    @Test void updatesProfileAndIdPreservingUsernameRoleAndPassword() {
        User actor = member("old-id", "alice", UserType.ADMIN);
        User result = service.updateProfile(actor, "new-id", "New Name", "new@example.com", "+61 412 345 678", null);
        assertEquals(result, stored.get("new-id"));
        assertFalse(stored.containsKey("old-id"));
        assertEquals("New Name", result.fullName());
        assertEquals("new@example.com", result.email());
        assertEquals("+61 412 345 678", result.phone());
        assertEquals(actor.username(), result.username());
        assertEquals(UserType.ADMIN, result.type());
        assertEquals(actor.passwordHash(), result.passwordHash());
        assertTrue(hasher.verify("old".toCharArray(), result.passwordHash()));
    }

    @Test void changedPasswordIsHashedAndVerifiable() {
        User actor = member("id", "alice", UserType.GENERAL);
        User result = service.updateProfile(actor, "id", actor.fullName(), actor.email(), actor.phone(), "new".toCharArray());
        assertNotEquals("new", result.passwordHash());
        assertNotEquals(actor.passwordHash(), result.passwordHash());
        assertTrue(hasher.verify("new".toCharArray(), result.passwordHash()));
        assertFalse(hasher.verify("old".toCharArray(), result.passwordHash()));
        assertEquals(1, stored.size());
    }

    @Test void rejectsDuplicateIdWithoutChangingEitherAccount() {
        User actor = member("id", "alice", UserType.GENERAL);
        User other = member("other", "bob", UserType.GENERAL);
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(actor, "other", "Name", actor.email(), actor.phone(), "new".toCharArray()));
        assertEquals(actor, stored.get("id"));
        assertEquals(other, stored.get("other"));
    }

    @Test void rejectsGuestsMissingAccountsAndMismatchedIdentity() {
        User actor = member("id", "alice", UserType.GENERAL);
        for (User invalid : Arrays.asList(null,
                new User("id", "alice", "", "", "", "", UserType.GUEST),
                new User("missing", "alice", "", "", "", "", UserType.GENERAL),
                new User("id", "bob", "", "", "", "", UserType.GENERAL))) {
            assertThrows(SecurityException.class, () -> service.updateProfile(invalid, "new", "Name", actor.email(), actor.phone(), null));
        }
        assertEquals(actor, stored.get("id"));
    }

    @Test void usesStoredRoleAndHashEvenWithStaleActor() {
        User actor = member("id", "alice", UserType.ADMIN);
        User current = member("id", "alice", UserType.GENERAL);
        User result = service.updateProfile(actor, "id", "Name", current.email(), current.phone(), null);
        assertEquals(UserType.GENERAL, result.type());
        assertEquals(current.passwordHash(), result.passwordHash());
    }

    @Test void rejectsInvalidFieldsBeforeWriting() {
        User actor = member("id", "alice", UserType.GENERAL);
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(actor, " ", "Name", actor.email(), actor.phone(), null));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(actor, "id", "", actor.email(), actor.phone(), null));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(actor, "id", "Name", "invalid", actor.phone(), null));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(actor, "id", "Name", actor.email(), "abc", null));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(actor, "id", "Name", actor.email(), actor.phone(), new char[0]));
        assertEquals(actor, stored.get("id"));
    }

    @Test void rejectsNullBlankAndPaddedProfileFieldsWithoutWriting() {
        User actor = member("id", "alice", UserType.GENERAL);
        for (int field = 0; field < 4; field++) {
            for (String invalid : Arrays.asList(null, "", "   ", " padded ")) {
                String[] values = {actor.idKey(), actor.fullName(), actor.email(), actor.phone()};
                values[field] = invalid;
                assertThrows(IllegalArgumentException.class, () -> service.updateProfile(
                        actor, values[0], values[1], values[2], values[3], null));
                assertEquals(Map.of(actor.idKey(), actor), stored);
            }
        }
    }

    @Test void rejectsPhoneWithTooFewDigitsWithoutWriting() {
        User actor = member("id", "alice", UserType.GENERAL);
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(
                actor, "new-id", "Name", actor.email(), "123-456", null));
        assertEquals(Map.of(actor.idKey(), actor), stored);
    }

    @Test void rejectsAccountThatHasBecomeAGuest() {
        User actor = member("id", "alice", UserType.GENERAL);
        User guest = member("id", "alice", UserType.GUEST);
        assertThrows(SecurityException.class, () -> service.updateProfile(
                actor, "new-id", "Name", actor.email(), actor.phone(), null));
        assertEquals(Map.of(guest.idKey(), guest), stored);
    }
}
