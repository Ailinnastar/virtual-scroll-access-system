package vsas.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import vsas.model.User;

/** Persisted records of the user actions shown to administrators. */
public final class ActivityLog {
    public enum Action { PROFILE_NAME_CHANGED, SCROLL_UPLOADED, SCROLL_DELETED }

    public record Event(Instant timestamp, String actorId, String actorUsername,
                        Action action, String targetId) { }

    private final Path file;
    private final List<Event> events = new ArrayList<>();
    private int persistedCount;

    /** In-memory log for embedded servers and tests. */
    public ActivityLog() {
        file = null;
    }

    /** Loads previous entries and stores future entries under the configured data directory. */
    public ActivityLog(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "Activity log path is required");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isEmpty()) events.add(parse(line));
            }
        }
        persistedCount = events.size();
    }

    /** Refuses a new mutation if earlier events cannot be saved or storage is unavailable. */
    public synchronized void ensureAvailable() throws IOException {
        if (file == null) return;
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Activity log path is not a regular file");
        }
        if (persistedCount < events.size()) {
            persist();
        } else {
            Path probe = Files.createTempFile(parent, "activity-probe-", ".tmp");
            Files.delete(probe);
        }
    }

    public synchronized void record(User actor, Action action, String targetId) throws IOException {
        Objects.requireNonNull(actor, "Actor is required");
        Objects.requireNonNull(action, "Action is required");
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("Target ID is required");
        Event event = new Event(Instant.now(), actor.idKey(), actor.username(), action, targetId);
        events.add(event);
        persist();
    }

    public synchronized List<Event> list() {
        List<Event> newestFirst = new ArrayList<>(events);
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    /** Retrying here lets an administrator see whether a failed write has recovered. */
    public synchronized boolean persistencePending() {
        if (persistedCount < events.size()) {
            try {
                persist();
            } catch (IOException ignored) {
                return true;
            }
        }
        return false;
    }

    private void persist() throws IOException {
        if (file == null) {
            persistedCount = events.size();
            return;
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "activity-", ".tmp");
        try {
            StringBuilder contents = new StringBuilder();
            for (Event event : events) contents.append(format(event)).append('\n');
            Files.writeString(temporary, contents, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            persistedCount = events.size();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String format(Event event) {
        return event.timestamp() + "\t" + event.action() + "\t" + encode(event.actorId())
                + "\t" + encode(event.actorUsername()) + "\t" + encode(event.targetId());
    }

    private static Event parse(String line) throws IOException {
        try {
            String[] fields = line.split("\t", -1);
            if (fields.length != 5) throw new IllegalArgumentException("Wrong field count");
            return new Event(Instant.parse(fields[0]), decode(fields[2]), decode(fields[3]),
                    Action.valueOf(fields[1]), decode(fields[4]));
        } catch (RuntimeException error) {
            throw new IOException("Activity log contains an invalid entry", error);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
