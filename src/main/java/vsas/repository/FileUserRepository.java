package vsas.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vsas.model.User;
import vsas.model.UserType;

/**
 * Small durable repository for the assignment application.
 *
 * <p>Fields are Base64 encoded to keep the line format unambiguous. Passwords are already salted
 * hashes when they reach this class; plaintext passwords must never be passed to a repository.</p>
 */
public final class FileUserRepository implements UserRepository {
    private static final int FIELD_COUNT = 7;
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final Path file;
    private final Map<String, User> users = new LinkedHashMap<>();

    public FileUserRepository(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        Set<String> views = this.file.getFileSystem().supportedFileAttributeViews();
        if (!views.contains("posix") && !views.contains("acl")) {
            throw new IOException("Credential storage requires POSIX permissions or ACL support");
        }
        load();
    }

    @Override
    public synchronized Optional<User> findById(String idKey) {
        return Optional.ofNullable(users.get(idKey));
    }

    @Override
    public synchronized Optional<User> findByUsername(String username) {
        return users.values().stream().filter(user -> user.username().equals(username)).findFirst();
    }

    @Override
    public synchronized List<User> findAll() {
        return List.copyOf(users.values());
    }

    @Override
    public synchronized void save(User user) {
        users.put(user.idKey(), user);
        persist();
    }

    @Override
    public synchronized void delete(String idKey) {
        if (users.remove(idKey) != null) persist();
    }

    private void load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        protectCredentials(file);
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != FIELD_COUNT && fields.length != FIELD_COUNT + 1) {
                throw new IOException("Invalid user data at line " + lineNumber);
            }
            try {
                User user = new User(decode(fields[0]), decode(fields[1]), decode(fields[2]),
                        decode(fields[3]), decode(fields[4]), decode(fields[5]),
                        UserType.valueOf(decode(fields[6])), fields.length == 8 ? decode(fields[7]) : "");
                users.put(user.idKey(), user);
            } catch (IllegalArgumentException error) {
                // Parsing exceptions may contain a stored field. Keep those values out of logs.
                throw new IOException("Invalid user data at line " + lineNumber);
            }
        }
    }

    private void persist() {
        Path parent = file.getParent();
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                // Restrict the empty file before writing any password hashes into it.
                protectCredentials(temporary);
                List<String> lines = new ArrayList<>();
                for (User user : users.values()) {
                    lines.add(String.join("\t", encode(user.idKey()), encode(user.username()),
                            encode(user.passwordHash()), encode(user.fullName()), encode(user.email()),
                            encode(user.phone()), encode(user.type().name()), encode(user.picture())));
                }
                Files.write(temporary, lines, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
                protectCredentials(file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Could not save user data", error);
        }
    }

    private static void protectCredentials(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Credential storage must be a regular file, not a symbolic link");
        }
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                posix.setPermissions(PRIVATE_PERMISSIONS);
                if (!posix.readAttributes().permissions().equals(PRIVATE_PERMISSIONS)) {
                    throw new IOException("Credential storage could not enforce owner-only permissions; "
                            + "choose a private data directory on a supported filesystem");
                }
                return;
            }

            AclFileAttributeView acl = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl != null) {
                var owner = acl.getOwner();
                var ownerPermissions = EnumSet.allOf(AclEntryPermission.class);
                AclEntry ownerEntry = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(ownerPermissions)
                        .build();
                acl.setAcl(List.of(ownerEntry));
                List<AclEntry> actual = acl.getAcl();
                boolean ownerHasAccess = actual.stream().anyMatch(entry ->
                        entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner)
                                && entry.permissions().containsAll(ownerPermissions));
                boolean anotherPrincipalHasAccess = actual.stream().anyMatch(entry ->
                        entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                                && !entry.permissions().isEmpty());
                if (!ownerHasAccess || anotherPrincipalHasAccess) {
                    throw new IOException("Credential storage could not enforce an owner-only ACL");
                }
                return;
            }
        } catch (UnsupportedOperationException error) {
            throw new IOException("Credential storage does not support owner-only permissions", error);
        }
        throw new IOException("Credential storage requires POSIX permissions or ACL support");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
