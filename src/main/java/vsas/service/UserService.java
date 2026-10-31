package vsas.service;

import java.util.Locale;
import java.util.Objects;
import java.math.BigInteger;
import java.util.function.Supplier;
import vsas.model.User;
import vsas.model.UserType;
import vsas.repository.UserRepository;
import vsas.security.PasswordHasher;

public class UserService {
    private static final int USER_ID_GENERATION_ATTEMPTS = 32;
    private final UserRepository users;
    private final PasswordHasher passwords;
    private final Supplier<String> userIdGenerator;
    private final java.time.Clock recoveryClock;
    private final RecoveryDigestFactory recoveryDigests;

    @FunctionalInterface
    interface RecoveryDigestFactory {
        java.security.MessageDigest create(String algorithm) throws java.security.NoSuchAlgorithmException;
    }
    private final java.util.Map<String, RecoveryGrant> recoveryGrants = new java.util.HashMap<>();
    private record RecoveryGrant(String id, String passwordHash, java.time.Instant expires) { }


    public UserService(UserRepository users, PasswordHasher passwords) {
        this(users, passwords, sequentialUserIds(users));
    }

    UserService(UserRepository users, PasswordHasher passwords,
                Supplier<String> userIdGenerator) {
        this(users, passwords, userIdGenerator, java.time.Clock.systemUTC());
    }

    UserService(UserRepository users, PasswordHasher passwords,
                Supplier<String> userIdGenerator, java.time.Clock recoveryClock) {
        this(users, passwords, userIdGenerator, recoveryClock, java.security.MessageDigest::getInstance);
    }

    UserService(UserRepository users, PasswordHasher passwords,
                Supplier<String> userIdGenerator, java.time.Clock recoveryClock,
                RecoveryDigestFactory recoveryDigests) {
        this.recoveryDigests = Objects.requireNonNull(recoveryDigests);
        this.recoveryClock = Objects.requireNonNull(recoveryClock);
        this.users = Objects.requireNonNull(users, "User repository is required");
        this.passwords = Objects.requireNonNull(passwords, "Password hasher is required");
        this.userIdGenerator = Objects.requireNonNull(userIdGenerator,
                "User ID generator is required");
    }

    public User register(String requestedIdKey, String username, char[] password,
                         String fullName, String email, String phone) {
        validateProfileFields(fullName, email, phone);
        requireText(username, "Username");
        requirePassword(password);
        synchronized (users) {
            if (users.findByUsername(username).isPresent()) {
                throw new IllegalArgumentException("Username is already in use");
            }
            String idKey = assignUserId(requestedIdKey);
            User created = new User(idKey, username, passwords.hash(password),
                    fullName, email, phone, UserType.GENERAL);
            users.save(created);
            return created;
        }
    }

    /** Only a currently authorised administrator may issue an out-of-band recovery link. */
    public String issuePasswordRecovery(User actor, String username) {
        synchronized (users) {
            if (actor == null || users.findById(actor.idKey())
                    .filter(u -> u.type() == UserType.ADMIN && u.username().equals(actor.username())).isEmpty()) {
                throw new SecurityException("Administrator access is required");
            }
            User target = users.findByUsername(username)
                    .filter(u -> u.type() != UserType.GUEST)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));
            recoveryGrants.entrySet().removeIf(entry -> entry.getValue().id().equals(target.idKey())
                    || !entry.getValue().expires().isAfter(recoveryClock.instant()));
            byte[] bytes = new byte[32];
            new java.security.SecureRandom().nextBytes(bytes);
            String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            recoveryGrants.put(recoveryDigest(token), new RecoveryGrant(target.idKey(),
                    target.passwordHash(), recoveryClock.instant().plusSeconds(900)));
            return token;
        }
    }

    public User recoverPassword(String token, char[] newPassword) {
        requirePassword(newPassword);
        synchronized (users) {
            String digest = recoveryDigest(token);
            RecoveryGrant grant = recoveryGrants.get(digest);
            User target = grant == null ? null : users.findById(grant.id()).orElse(null);
            if (grant == null || !grant.expires().isAfter(recoveryClock.instant()) || target == null
                    || target.type() == UserType.GUEST || !target.passwordHash().equals(grant.passwordHash())) {
                throw new IllegalArgumentException("Recovery link is invalid or expired. Contact an administrator for a new link.");
            }
            User updated = new User(target.idKey(), target.username(), passwords.hash(newPassword),
                    target.fullName(), target.email(), target.phone(), target.type(), target.picture());
            users.save(updated);
            recoveryGrants.remove(digest);
            return updated;
        }
    }

    private String recoveryDigest(String token) {
        if (token == null || token.length() != 43) throw new IllegalArgumentException("Invalid recovery link");
        try {
            return java.util.HexFormat.of().formatHex(recoveryDigests.create("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String uploaderUsername(String idKey) {
        return users.findById(idKey).map(User::username).orElse("Deleted user");
    }

    /** Revalidates the session identity before exposing account-private favourites. */
    public User requireCurrentMember(User actor) {
        if (actor == null || actor.type() == UserType.GUEST) {
            throw new SecurityException("Sign in to save and view favourites");
        }
        return users.findById(actor.idKey())
                .filter(user -> user.type() != UserType.GUEST
                        && user.username().equals(actor.username())
                        && user.passwordHash().equals(actor.passwordHash()))
                .orElseThrow(() -> new SecurityException("Account is no longer available. Please sign in again."));
    }

    public User login(String username, char[] password) {
        if (username == null || username.isBlank() || password == null || password.length == 0) {
            throw new SecurityException("Invalid username or password");
        }
        User user = users.findByUsername(username)
                .filter(found -> found.type() != UserType.GUEST)
                .filter(found -> passwords.verify(password, found.passwordHash()))
                .orElseThrow(() -> new SecurityException("Invalid username or password"));
        return user;
    }

    /**
     * Updates the account identified by a trusted, authenticated actor.
     * Pass null for newPassword to retain the current hash; all profile fields are required.
     * Use the returned user as the session identity after changing the ID key.
     * The repository must support replacing a user at the same ID via save.
     */
    public User updateProfile(User actor, String idKey, String fullName,
                              String email, String phone, char[] newPassword) {
        if (actor == null || actor.type() == UserType.GUEST) {
            throw new SecurityException("Sign in to update your profile");
        }
        validateProfile(idKey, fullName, email, phone);
        if (newPassword != null) requirePassword(newPassword);
        synchronized (users) {
            User current = users.findById(actor.idKey())
                    .filter(u -> Objects.equals(u.username(), actor.username()))
                    .filter(u -> u.type() != UserType.GUEST)
                    .orElseThrow(() -> new SecurityException("Account is no longer available"));
            boolean changedId = !current.idKey().equals(idKey);
            if (changedId && users.findById(idKey).isPresent()) {
                throw new IllegalArgumentException("ID key is already in use");
            }
            String hash = newPassword == null ? current.passwordHash() : passwords.hash(newPassword);
            User updated = new User(idKey, current.username(), hash,
                    fullName, email, phone, current.type(), current.picture());
            users.save(updated);
            if (changedId) users.delete(current.idKey());
            return updated;
        }
    }

    public User updatePicture(User actor, byte[] content) {
        if (actor == null || actor.type() == UserType.GUEST) {
            throw new SecurityException("Sign in to change your picture");
        }
        String picture = ProfilePicture.encode(content);
        synchronized (users) {
            User current = users.findByUsername(actor.username())
                    .filter(u -> u.idKey().equals(actor.idKey()))
                    .orElseThrow(() -> new SecurityException("Account is no longer available"));
            User updated = new User(current.idKey(), current.username(), current.passwordHash(),
                    current.fullName(), current.email(), current.phone(), current.type(), picture);
            users.save(updated);
            return updated;
        }
    }

    private static void validateProfile(String idKey, String fullName, String email, String phone) {
        validateIdKey(idKey);
        validateProfileFields(fullName, email, phone);
    }

    private static void validateIdKey(String idKey) {
        requireText(idKey, "ID key");
        if (!idKey.matches("[\\p{L}\\p{N}._-]+") || idKey.length() > 64) {
            throw new IllegalArgumentException(
                    "ID key may contain up to 64 letters, numbers, dots, underscores and hyphens");
        }
    }

    private static void validateProfileFields(String fullName, String email, String phone) {
        requireText(fullName, "Full name");
        requireText(email, "Email");
        requireText(phone, "Phone");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        String phoneDigits = phone.replaceAll("[^0-9]", "");
        boolean localNumber = phone.matches("[0-9() .-]+") && phoneDigits.length() == 10;
        boolean australianInternational = phone.startsWith("+61") && phoneDigits.length() == 11;
        if (!localNumber && !australianInternational) {
            throw new IllegalArgumentException("Enter a 10-digit phone number");
        }
    }

    private String assignUserId(String requestedIdKey) {
        if (requestedIdKey != null && !requestedIdKey.isBlank()) {
            validateIdKey(requestedIdKey);
            if (users.findById(requestedIdKey).isPresent()) {
                throw new IllegalArgumentException("ID key is already in use");
            }
            return requestedIdKey;
        }
        for (int attempt = 0; attempt < USER_ID_GENERATION_ATTEMPTS; attempt++) {
            String candidate = userIdGenerator.get();
            validateIdKey(candidate);
            if (users.findById(candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException("Could not assign a unique user ID");
    }

    static Supplier<String> sequentialUserIds(UserRepository users) {
        BigInteger[] lastAssigned = {BigInteger.ZERO};
        return () -> {
            BigInteger highest = users.findAll().stream()
                    .map(User::idKey)
                    .filter(id -> id.matches("USR-[0-9]+"))
                    .map(id -> new BigInteger(id.substring(4)))
                    .max(BigInteger::compareTo).orElse(BigInteger.ZERO);
            lastAssigned[0] = lastAssigned[0].max(highest).add(BigInteger.ONE);
            return "USR-" + String.format(Locale.ROOT, "%08d", lastAssigned[0]);
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must not have leading or trailing whitespace");
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password is required");
        }
    }
}
