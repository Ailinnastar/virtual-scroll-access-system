package vsas.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import vsas.model.User;
import vsas.model.UserType;

/** Stores scroll bookmarks for an authenticated account, without copying account credentials. */
public final class Favourites {
    private record Account(String idKey, String username) { }

    private final Path file;
    private Map<Account, Set<String>> favourites = new HashMap<>();

    /** In-memory storage for isolated application tests. */
    public Favourites() {
        file = null;
    }

    public Favourites(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "Favourites file is required")
                .toAbsolutePath().normalize();
        load();
    }

    /** Returns an immutable snapshot; later changes do not alter an earlier result. */
    public synchronized Set<String> list(User actor) {
        Set<String> saved = favourites.getOrDefault(account(actor), Set.of());
        return Collections.unmodifiableSet(new TreeSet<>(saved));
    }

    public synchronized boolean contains(User actor, String scrollId) {
        Account owner = account(actor);
        requireText(scrollId, "Scroll ID");
        return favourites.getOrDefault(owner, Set.of()).contains(scrollId);
    }

    /** Explicit desired state makes repeated requests safe, unlike a blind toggle. */
    public synchronized void set(User actor, String scrollId, boolean saved) throws IOException {
        Account owner = account(actor);
        requireText(scrollId, "Scroll ID");
        Set<String> updated = new TreeSet<>(favourites.getOrDefault(owner, Set.of()));
        boolean changed = saved ? updated.add(scrollId) : updated.remove(scrollId);
        if (!changed) return;

        Map<Account, Set<String>> next = new HashMap<>(favourites);
        if (updated.isEmpty()) next.remove(owner);
        else next.put(owner, updated);
        persist(next);
        favourites = next;
    }

    public synchronized void removeAccount(User actor) throws IOException {
        Account owner = account(actor);
        if (!favourites.containsKey(owner)) return;
        Map<Account, Set<String>> next = new HashMap<>(favourites);
        next.remove(owner);
        persist(next);
        favourites = next;
    }

    private static Account account(User actor) {
        if (actor == null || actor.type() == null || actor.type() == UserType.GUEST) {
            throw new SecurityException("Sign in to manage favourites");
        }
        requireText(actor.idKey(), "Account ID");
        requireText(actor.username(), "Username");
        return new Account(actor.idKey(), actor.username());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private void load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Favourites storage must be a regular file");
        }
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            String[] fields = line.split("\t", -1);
            try {
                if (fields.length != 3) throw new IllegalArgumentException();
                String idKey = decode(fields[0]);
                String username = decode(fields[1]);
                String scrollId = decode(fields[2]);
                requireText(idKey, "Account ID");
                requireText(username, "Username");
                requireText(scrollId, "Scroll ID");
                Set<String> saved = favourites.computeIfAbsent(
                        new Account(idKey, username), ignored -> new TreeSet<>());
                if (!saved.add(scrollId)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException error) {
                throw new IOException("Invalid favourites data at line " + lineNumber);
            }
        }
    }

    private void persist(Map<Account, Set<String>> next) throws IOException {
        if (file == null) return;
        Path parent = file.getParent();
        Files.createDirectories(parent);
        var accounts = new ArrayList<>(next.keySet());
        accounts.sort(Comparator.comparing(Account::idKey).thenComparing(Account::username));
        var lines = new ArrayList<String>();
        for (Account owner : accounts) {
            for (String scrollId : new TreeSet<>(next.get(owner))) {
                lines.add(String.join("\t", encode(owner.idKey()),
                        encode(owner.username()), encode(scrollId)));
            }
        }
        Path temporary = Files.createTempFile(parent, "favourites-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            // Fail safely on filesystems without atomic replacement; do not publish partial data.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString();
    }
}
