package vsas.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.model.User;
import vsas.model.UserType;
import static org.junit.jupiter.api.Assertions.*;

class FileUserRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void savesLoadsAndDeletesUsersAcrossRepositoryInstances() throws Exception {
        Path file = temporaryDirectory.resolve("users.db");
        User user = new User("id-1", "alice", "pbkdf2-hash-value",
                "Alice Example", "alice@example.com", "0412345678", UserType.GENERAL);
        FileUserRepository first = new FileUserRepository(file);
        first.save(user);

        String diskText = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(diskText.contains("alice@example.com"));
        assertFalse(diskText.contains("Alice Example"));

        FileUserRepository restarted = new FileUserRepository(file);
        assertEquals(user, restarted.findById("id-1").orElseThrow());
        assertEquals(user, restarted.findByUsername("alice").orElseThrow());
        assertEquals(java.util.List.of(user), restarted.findAll());
        restarted.delete("missing");
        restarted.delete("id-1");
        assertTrue(new FileUserRepository(file).findAll().isEmpty());
    }

    @Test void blankLinesDoNotCreatePhantomAccounts() throws Exception {
        Path file = temporaryDirectory.resolve("users.db");
        var repository = new FileUserRepository(file);
        var user = new User("id", "alice", "hash", "Alice", "a@example.com", "0412345678", UserType.GENERAL);
        repository.save(user);
        Files.writeString(file, "\n  \n" + Files.readString(file) + "\n");
        assertEquals(java.util.List.of(user), new FileUserRepository(file).findAll());
    }

    @Test void failedCredentialWriteReportsIoFailureWithoutExposingHash() throws Exception {
        Path parent = temporaryDirectory.resolve("blocked");
        Files.writeString(parent, "not a directory");
        var repository = new FileUserRepository(parent.resolve("users.db"));
        var user = new User("id", "alice", "sensitive-hash", "Alice", "a@example.com", "0412345678", UserType.GENERAL);
        var error = assertThrows(java.io.UncheckedIOException.class, () -> repository.save(user));
        assertEquals("Could not save user data", error.getMessage());
        assertFalse(error.getMessage().contains(user.passwordHash()));
        assertEquals("not a directory", Files.readString(parent));
    }

    @Test void reportsCorruptStoredData() throws Exception {
        Path file = temporaryDirectory.resolve("users.db");
        Files.writeString(file, "not\ta\tvalid\trecord", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new FileUserRepository(file));
    }

    @Test void keepsSavedAndReplacedCredentialFilesPrivate() throws Exception {
        Path file = temporaryDirectory.resolve("users.db");
        User user = new User("id-1", "alice", "private-hash-value",
                "Alice Example", "alice@example.com", "0412345678", UserType.GENERAL);
        FileUserRepository repository = new FileUserRepository(file);
        repository.save(user);
        assertOwnerOnly(file);

        repository.save(new User(user.idKey(), user.username(), user.passwordHash(),
                "Updated Alice", user.email(), user.phone(), user.type()));
        assertOwnerOnly(file);
        assertEquals("private-hash-value", new FileUserRepository(file)
                .findById(user.idKey()).orElseThrow().passwordHash());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(java.util.List.of(file), files.toList());
        }
    }

    @Test void restrictsExistingCredentialsBeforeLoadingThem() throws Exception {
        Path file = temporaryDirectory.resolve("users.db");
        Files.writeString(file, "");
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
        new FileUserRepository(file);
        assertOwnerOnly(file);
    }

    @Test void refusesToStoreCredentialsWithoutFilesystemAccessControls() throws Exception {
        var archive = java.net.URI.create("jar:" + temporaryDirectory.resolve("store.zip").toUri());
        try (var filesystem = java.nio.file.FileSystems.newFileSystem(
                archive, java.util.Map.of("create", "true"))) {
            Path file = filesystem.getPath("/users.db");
            assertThrows(IOException.class, () -> new FileUserRepository(file));
            assertFalse(Files.exists(file));
            try (var files = Files.list(file.getParent())) {
                assertEquals(0L, files.count());
            }
        }
    }

    @Test void doesNotLogStoredFieldsWhenDataIsCorrupt() throws Exception {
        Path file = temporaryDirectory.resolve("users.db");
        String hash = "sensitive-stored-hash-marker";
        String line = java.util.stream.Stream.of("id", "alice", hash, "Alice",
                        "alice@example.com", "0412345678", hash)
                .map(value -> java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("\t"));
        Files.writeString(file, line);
        IOException error = assertThrows(IOException.class, () -> new FileUserRepository(file));
        var stackTrace = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(stackTrace));
        assertFalse(stackTrace.toString().contains(hash));
    }

    @Test void rejectsSymbolicLinksWithoutChangingTheirTargets() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null);
        Path target = temporaryDirectory.resolve("target.txt");
        Files.writeString(target, "unchanged");
        var previousPermissions = Files.getPosixFilePermissions(target);
        Path link = temporaryDirectory.resolve("users.db");
        Files.createSymbolicLink(link, target);
        assertThrows(IOException.class, () -> new FileUserRepository(link));
        assertEquals("unchanged", Files.readString(target));
        assertEquals(previousPermissions, Files.getPosixFilePermissions(target));
    }

    private static void assertOwnerOnly(Path file) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    posix.readAttributes().permissions());
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertNotNull(acl, "Credential storage needs filesystem access controls");
        var owner = acl.getOwner();
        assertFalse(acl.getAcl().isEmpty());
        for (var entry : acl.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.permissions().isEmpty()) {
                assertEquals(owner, entry.principal(), "Another principal must not have access");
            }
        }
    }
}
