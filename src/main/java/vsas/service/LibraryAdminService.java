package vsas.service;

import java.util.List;
import java.util.Objects;
import vsas.model.ScrollStatistics;
import vsas.model.User;
import vsas.model.UserType;
import vsas.repository.ScrollRepository;
import vsas.repository.UserRepository;
import vsas.security.PasswordHasher;

/** Administrator accounts and scroll statistics. */
public final class LibraryAdminService implements AdminService {
    private final UserRepository users;
    private final java.util.function.Supplier<String> userIds;
    private final PasswordHasher passwords;
    private final ScrollRepository scrolls;
    private final LibraryScrollService library;

    public LibraryAdminService(UserRepository users, PasswordHasher passwords,
                               ScrollRepository scrolls, LibraryScrollService library) {
        this.users = Objects.requireNonNull(users, "User repository is required");
        this.userIds = UserService.sequentialUserIds(users);
        this.passwords = Objects.requireNonNull(passwords, "Password hasher is required");
        this.scrolls = Objects.requireNonNull(scrolls, "Scroll repository is required");
        this.library = Objects.requireNonNull(library, "Scroll service is required");
    }

    @Override
    public void initialiseAdmin(String username, char[] password) {
        requireText(username, "Username");
        requirePassword(password);
        synchronized (users) {
            boolean adminExists = users.findAll().stream().anyMatch(user -> user.type() == UserType.ADMIN);
            if (adminExists || users.findByUsername(username).isPresent()) return;
            users.save(new User(username, username, passwords.hash(password),
                    "Library Admin", "admin@vsas.local", "0400000000", UserType.ADMIN));
        }
    }

    @Override
    public List<User> listUsers(User actor) {
        requireAdmin(actor);
        return users.findAll();
    }

    @Override
    public User addUser(User actor, String username, char[] password,
                        String fullName, String email, String phone, UserType type) {
        requireAdmin(actor);
        synchronized (users) {
            return addUser(actor, userIds.get(), username, password, fullName, email, phone, type);
        }
    }

    @Override
    public User addUser(User actor, String idKey, String username, char[] password,
                        String fullName, String email, String phone, UserType type) {
        requireAdmin(actor);
        if (type != UserType.GENERAL && type != UserType.ADMIN) {
            throw new IllegalArgumentException("Choose a general or admin account");
        }
        if (idKey == null || idKey.isBlank()) {
            return addUser(actor, username, password, fullName, email, phone, type);
        }
        validateProfile(idKey, fullName, email, phone);
        requireText(username, "Username");
        requirePassword(password);
        synchronized (users) {
            if (users.findById(idKey).isPresent()) {
                throw new IllegalArgumentException("ID key is already in use");
            }
            if (users.findByUsername(username).isPresent()) {
                throw new IllegalArgumentException("Username is already in use");
            }
            User created = new User(idKey, username, passwords.hash(password),
                    fullName, email, phone, type);
            users.save(created);
            return created;
        }
    }

    @Override
    public void deleteUser(User actor, String targetIdKey) {
        User admin = requireAdmin(actor);
        if (targetIdKey == null || targetIdKey.isBlank()) {
            throw new IllegalArgumentException("User ID is required");
        }
        synchronized (users) {
            User target = users.findById(targetIdKey)
                    .orElseThrow(() -> new IllegalArgumentException("User was not found"));
            if (target.idKey().equals(admin.idKey())) {
                throw new IllegalArgumentException("You cannot delete your own account");
            }
            long admins = users.findAll().stream().filter(user -> user.type() == UserType.ADMIN).count();
            if (target.type() == UserType.ADMIN && admins <= 1) {
                throw new IllegalArgumentException("The last administrator cannot be deleted");
            }
            users.delete(target.idKey());
        }
    }

    public void deleteScroll(User actor, String scrollId) {
        library.removeAny(actor, scrollId);
    }

    @Override
    public List<ScrollStatistics> statistics(User actor) {
        requireAdmin(actor);
        return scrolls.statistics();
    }

    /** Rechecks the stored account, including after an old session's account is deleted. */
    public User requireAdmin(User actor) {
        if (actor == null || actor.type() == UserType.GUEST) {
            throw new SecurityException("Sign in as an administrator");
        }
        User current = users.findById(actor.idKey())
                .filter(user -> user.type() == UserType.ADMIN
                        && user.username().equals(actor.username())
                        && user.passwordHash().equals(actor.passwordHash()))
                .orElseThrow(() -> new ForbiddenException("Only an administrator can do that"));
        return current;
    }

    private static void validateProfile(String idKey, String fullName, String email, String phone) {
        requireText(idKey, "ID key");
        requireText(fullName, "Full name");
        requireText(email, "Email");
        requireText(phone, "Phone");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        if (!idKey.matches("[\\p{L}\\p{N}._-]+") || idKey.length() > 64) {
            throw new IllegalArgumentException(
                    "ID key may contain up to 64 letters, numbers, dots, underscores and hyphens");
        }
        String phoneDigits = phone.replaceAll("[^0-9]", "");
        boolean localNumber = phone.matches("[0-9() .-]+") && phoneDigits.length() == 10;
        boolean australianInternational = phone.startsWith("+61") && phoneDigits.length() == 11;
        if (!localNumber && !australianInternational) {
            throw new IllegalArgumentException("Enter a 10-digit phone number");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password is required");
        }
    }
}
