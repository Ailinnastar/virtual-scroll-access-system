package vsas.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;
import java.util.Optional;
import vsas.model.Scroll;
import vsas.model.ScrollStatistics;

/**
 * Stores scroll metadata and historical counters as tab-separated files, and binary
 * contents as separate files. Removing a scroll deletes its file and marks its ID removed.
 */
public final class FileScrollRepository implements ScrollRepository {
    private static final int SCROLL_FIELDS = 5;
    private final Path directory;
    private final Path indexFile;
    private final Path statsFile;
    private final Map<String, Scroll> scrolls = new LinkedHashMap<>();
    private final Map<String, long[]> statistics = new LinkedHashMap<>();
    private final Set<String> removedIds = new LinkedHashSet<>();

    public FileScrollRepository(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.indexFile = this.directory.resolve("index.db");
        this.statsFile = this.directory.resolve("stats.db");
        Files.createDirectories(this.directory.resolve("files"));
        loadIndex();
        loadStats();
    }

    @Override
    public synchronized Optional<Scroll> findById(String id) {
        return Optional.ofNullable(scrolls.get(id));
    }

    @Override
    public synchronized List<Scroll> findAll() {
        return List.copyOf(scrolls.values());
    }

    @Override
    public synchronized byte[] readContent(String id) {
        if (!scrolls.containsKey(id)) throw new NoSuchElementException("Scroll was not found");
        try {
            return Files.readAllBytes(contentPath(id));
        } catch (IOException error) {
            throw new UncheckedIOException("Could not read scroll file", error);
        }
    }

    @Override
    public synchronized void save(Scroll scroll, byte[] content) {
        Path path = contentPath(scroll.id());
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "scroll", ".tmp");
            try {
                Files.write(temporary, content);
                move(temporary, path);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Could not save scroll file", error);
        }
        scrolls.put(scroll.id(), scroll);
        persistIndex();
    }

    @Override
    public synchronized void delete(String id) {
        scrolls.remove(id);
        statistics.putIfAbsent(id, new long[2]);
        removedIds.add(id);
        try {
            Files.deleteIfExists(contentPath(id));
        } catch (IOException error) {
            throw new UncheckedIOException("Could not delete scroll file", error);
        }
        persistIndex();
        persistStats();
    }

    @Override
    public synchronized void recordUpload(String id) {
        statistics.computeIfAbsent(id, ignored -> new long[2])[0]++;
        removedIds.remove(id);
        persistStats();
    }

    @Override
    public synchronized void recordDownload(String id) {
        statistics.computeIfAbsent(id, ignored -> new long[2])[1]++;
        persistStats();
    }

    @Override
    public synchronized List<ScrollStatistics> statistics() {
        List<ScrollStatistics> rows = new ArrayList<>();
        statistics.forEach((id, counts) -> rows.add(
                new ScrollStatistics(id, counts[0], counts[1], removedIds.contains(id))));
        return List.copyOf(rows);
    }

    private void loadIndex() throws IOException {
        if (!Files.exists(indexFile)) return;
        int lineNumber = 0;
        for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != SCROLL_FIELDS) {
                throw new IOException("Invalid scroll index at line " + lineNumber);
            }
            try {
                Scroll scroll = new Scroll(decode(fields[0]), decode(fields[1]), decode(fields[2]),
                        LocalDate.parse(decode(fields[3])), decode(fields[4]));
                if (!Files.exists(contentPath(scroll.id()))) {
                    throw new IOException("Missing binary for scroll " + scroll.id());
                }
                scrolls.put(scroll.id(), scroll);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid scroll index at line " + lineNumber, error);
            }
        }
    }

    private void loadStats() throws IOException {
        if (!Files.exists(statsFile)) return;
        int lineNumber = 0;
        for (String line : Files.readAllLines(statsFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 && fields.length != 4) {
                throw new IOException("Invalid scroll statistics at line " + lineNumber);
            }
            try {
                String id = decode(fields[0]);
                statistics.put(id, new long[]{
                        Long.parseLong(decode(fields[1])), Long.parseLong(decode(fields[2]))});
                if (fields.length == 4 && "1".equals(decode(fields[3]))) removedIds.add(id);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid scroll statistics at line " + lineNumber, error);
            }
        }
    }

    private void persistIndex() {
        List<String> lines = new ArrayList<>();
        for (Scroll scroll : scrolls.values()) {
            lines.add(String.join("\t", encode(scroll.id()), encode(scroll.name()),
                    encode(scroll.uploaderId()), encode(scroll.uploadDate().toString()),
                    encode(scroll.originalFileName())));
        }
        writeLines(indexFile, lines);
    }

    private void persistStats() {
        List<String> lines = new ArrayList<>();
        statistics.forEach((id, counts) -> lines.add(String.join("\t",
                encode(id), encode(Long.toString(counts[0])), encode(Long.toString(counts[1])),
                encode(removedIds.contains(id) ? "1" : "0"))));
        writeLines(statsFile, lines);
    }

    private Path contentPath(String id) {
        String fileName = HexFormat.of().formatHex(id.getBytes(StandardCharsets.UTF_8));
        return directory.resolve("files").resolve(fileName);
    }

    private static void writeLines(Path file, List<String> lines) {
        Path parent = file.getParent();
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, lines, StandardCharsets.UTF_8);
                move(temporary, file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Could not save scroll data", error);
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
