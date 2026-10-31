package vsas.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import vsas.model.Scroll;
import vsas.model.ScrollFilter;
import vsas.model.ScrollStatistics;
import vsas.model.User;
import vsas.model.UserType;
import vsas.repository.ScrollRepository;

/** Upload, browse, download and owner-only removal for digital scrolls. */
public final class LibraryScrollService implements ScrollService {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final char[] ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private final ScrollRepository scrolls;
    private final SecureRandom random = new SecureRandom();

    public LibraryScrollService(ScrollRepository scrolls) {
        this.scrolls = Objects.requireNonNull(scrolls, "Scroll repository is required");
    }

    @Override
    public Scroll upload(User actor, String name, Path binaryFile) {
        Objects.requireNonNull(binaryFile, "Scroll file is required");
        try {
            return upload(actor, name, null, binaryFile.getFileName().toString(), Files.readAllBytes(binaryFile));
        } catch (IOException error) {
            throw new UncheckedIOException("Could not read the scroll file", error);
        }
    }

    /**
     * Stores a binary scroll for a signed-in member.
     * The scroll id argument is ignored. Every upload receives a new random id,
     * including after an older scroll with a chosen id has been deleted.
     */
    public Scroll upload(User actor, String name, String scrollId, String originalFileName, byte[] content) {
        requireMember(actor, "Sign in to upload a scroll");
        String cleanName = requireName(name);
        byte[] bytes = requireContent(content);
        String cleanFile = cleanFileName(originalFileName);
        synchronized (scrolls) {
            String id = randomId();
            if (nameTaken(cleanName, null)) {
                throw new IllegalArgumentException("Scroll name is already in use");
            }
            Scroll created = new Scroll(id, cleanName, actor.idKey(), LocalDate.now(), cleanFile);
            scrolls.save(created, bytes);
            scrolls.recordUpload(id);
            return created;
        }
    }

    @Override
    public Scroll update(User actor, String scrollId, String name, Path replacementFile) {
        requireMember(actor, "Sign in to edit a scroll");

        byte[] replacement = null;
        String replacementName = null;

        if (replacementFile != null) {
            try {
                replacement = requireContent(Files.readAllBytes(replacementFile));
                replacementName = cleanFileName(
                        replacementFile.getFileName().toString()
                );
            } catch (IOException error) {
                throw new UncheckedIOException(
                        "Could not read the replacement file",
                        error
                );
            }
        }

        return updateStored(
                actor,
                scrollId,
                name,
                replacementName,
                replacement
        );
    }

    public Scroll update(
            User actor,
            String scrollId,
            String name,
            String replacementFileName,
            byte[] replacementContent
    ) {
        requireMember(actor, "Sign in to edit a scroll");

        byte[] replacement = replacementContent == null
                ? null
                : requireContent(replacementContent);

        String replacementName = replacementContent == null
                ? null
                : cleanFileName(replacementFileName);

        return updateStored(
                actor,
                scrollId,
                name,
                replacementName,
                replacement
        );
    }

    private Scroll updateStored(
            User actor,
            String scrollId,
            String name,
            String replacementName,
            byte[] replacement
    ) {
        synchronized (scrolls) {
            Scroll current = requireScroll(scrollId);
            requireOwner(actor, current);

            String cleanName = name == null || name.isBlank()
                    ? current.name()
                    : requireName(name);

            if (nameTaken(cleanName, current.id())) {
                throw new IllegalArgumentException(
                        "Scroll name is already in use"
                );
            }

            Scroll updated = new Scroll(
                    current.id(),
                    cleanName,
                    current.uploaderId(),
                    current.uploadDate(),
                    replacementName == null
                            ? current.originalFileName()
                            : replacementName
            );

            scrolls.save(
                    updated,
                    replacement == null
                            ? scrolls.readContent(current.id())
                            : replacement
            );

            return updated;
        }
    }

    @Override
    public void remove(User actor, String scrollId) {
        requireMember(actor, "Sign in to delete a scroll");
        synchronized (scrolls) {
            Scroll current = requireScroll(scrollId);
            requireOwner(actor, current);
            scrolls.delete(current.id());
        }
    }

    @Override
    public List<Scroll> search(ScrollFilter filter) {
        synchronized (scrolls) {
            List<Scroll> matches = new ArrayList<>();
            for (Scroll scroll : scrolls.findAll()) {
                if (matches(scroll, filter)) matches.add(scroll);
            }
            matches.sort(Comparator.comparing(Scroll::uploadDate).reversed()
                    .thenComparing(Scroll::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(matches);
        }
    }

    @Override
    public byte[] preview(String scrollId) {
        synchronized (scrolls) {
            requireScroll(scrollId);
            byte[] content = scrolls.readContent(scrollId);
            try {
                return new ScrollPreviewer().preview(content);
            } catch (IllegalArgumentException ignored) {
                return ("Binary scroll (" + content.length + " bytes)").getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    @Override
    public void download(User actor, String scrollId, Path destination) {
        byte[] content = downloadBytes(actor, scrollId);
        try {
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            Files.write(destination, content);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not save the download", error);
        }
    }

    public byte[] downloadBytes(User actor, String scrollId) {
        requireMember(actor, "Sign in to download a scroll");
        synchronized (scrolls) {
            requireScroll(scrollId);
            byte[] content = scrolls.readContent(scrollId);
            scrolls.recordDownload(scrollId);
            return content;
        }
    }

    public long downloadCount(String scrollId) {
        synchronized (scrolls) {
            ScrollStatistics row = statisticsFor(scrollId);
            return row == null ? 0 : row.downloads();
        }
    }

    public List<ScrollStatistics> statistics() {
        synchronized (scrolls) {
            return scrolls.statistics();
        }
    }

    /** Removes any member's scroll. The file and library entry go away; counts stay for the administrator. */
    public void removeAny(User actor, String scrollId) {
        if (actor == null || actor.type() != UserType.ADMIN) {
            throw new ForbiddenException("Only an administrator can remove another member's scroll");
        }
        synchronized (scrolls) {
            requireScroll(scrollId);
            scrolls.delete(scrollId);
        }
    }

    private ScrollStatistics statisticsFor(String scrollId) {
        for (ScrollStatistics row : scrolls.statistics()) {
            if (row.scrollId().equals(scrollId)) return row;
        }
        return null;
    }

    private boolean nameTaken(String name, String ignoredId) {
        for (Scroll scroll : scrolls.findAll()) {
            if (scroll.id().equals(ignoredId)) continue;
            if (scroll.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private String randomId() {
        for (int attempt = 0; attempt < 32; attempt++) {
            StringBuilder id = new StringBuilder("SCR-");
            for (int i = 0; i < 8; i++) id.append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]);
            String value = id.toString();
            if (scrolls.findById(value).isEmpty() && statisticsFor(value) == null) return value;
        }
        throw new IllegalStateException("Could not assign a scroll ID");
    }

    private Scroll requireScroll(String scrollId) {
        if (scrollId == null || scrollId.isBlank()) {
            throw new IllegalArgumentException("Scroll ID is required");
        }
        return scrolls.findById(scrollId)
                .orElseThrow(() -> new NoSuchElementException("Scroll was not found"));
    }

    private static void requireOwner(User actor, Scroll scroll) {
        if (!actor.idKey().equals(scroll.uploaderId())) {
            throw new ForbiddenException("Only the owner can change this scroll");
        }
    }

    private static void requireMember(User actor, String message) {
        if (actor == null || actor.type() == UserType.GUEST) {
            throw new SecurityException(message);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Scroll name is required");
        String clean = name.strip();
        if (!clean.equals(name) || clean.length() > 80 || clean.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter a scroll name up to 80 characters");
        }
        return clean;
    }

    private static byte[] requireContent(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Choose a scroll file to upload");
        }
        if (content.length > MAX_BYTES) throw new IllegalArgumentException("Scroll file must be 8 MB or smaller");
        return content.clone();
    }

    private static String cleanFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) return "scroll.bin";
        String name = Path.of(originalFileName).getFileName().toString().strip();
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.chars().anyMatch(Character::isISOControl)) {
            return "scroll.bin";
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static boolean matches(Scroll scroll, ScrollFilter filter) {
        if (filter == null) return true;
        if (present(filter.uploaderId()) && !scroll.uploaderId().equals(filter.uploaderId())) return false;
        if (present(filter.scrollId()) && !scroll.id().equals(filter.scrollId())) return false;
        if (present(filter.name()) && !scroll.name().toLowerCase(Locale.ROOT)
                .contains(filter.name().strip().toLowerCase(Locale.ROOT))) return false;
        return filter.uploadDate() == null || scroll.uploadDate().equals(filter.uploadDate());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
