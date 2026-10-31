package vsas.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.model.Scroll;
import vsas.model.ScrollStatistics;
import static org.junit.jupiter.api.Assertions.*;

class FileScrollRepositoryTest {
    @TempDir Path directory;

    private Scroll scroll() {
        return new Scroll("sample", "Unicode notes 知識", "owner", LocalDate.of(2026, 10, 22), "notes.bin");
    }

    @Test void deleteReportsIoFailureForNonEmptyContentDirectory() throws Exception {
        var repository = new FileScrollRepository(directory);
        repository.save(scroll(), new byte[]{1});
        String name = java.util.HexFormat.of().formatHex(scroll().id().getBytes(StandardCharsets.UTF_8));
        Path content = directory.resolve("files").resolve(name);
        Files.delete(content);
        Files.createDirectory(content);
        Files.writeString(content.resolve("occupied"), "keep me");
        var error = assertThrows(UncheckedIOException.class, () -> repository.delete(scroll().id()));
        assertEquals("Could not delete scroll file", error.getMessage());
        assertEquals("keep me", Files.readString(content.resolve("occupied")));
    }

    @Test void indexWriteFailureIsReportedAndTemporaryFilesAreRemoved() throws Exception {
        var repository = new FileScrollRepository(directory);
        Files.createDirectory(directory.resolve("index.db"));
        Files.writeString(directory.resolve("index.db/occupied"), "keep me");
        var error = assertThrows(UncheckedIOException.class, () -> repository.save(scroll(), new byte[]{1}));
        assertEquals("Could not save scroll data", error.getMessage());
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void legacyStatisticsConstructorRepresentsAnActiveScroll() {
        var stats = new ScrollStatistics("legacy", 3, 7);
        assertEquals("legacy", stats.scrollId());
        assertEquals(3, stats.uploads());
        assertEquals(7, stats.downloads());
        assertFalse(stats.removed());
    }

    @Test void binaryReplacementMetadataAndCountersSurviveRestart() throws Exception {
        var repository = new FileScrollRepository(directory);
        byte[] original = {0, 1, (byte) 0xff, '\n'};
        repository.save(scroll(), original);
        repository.recordUpload("sample");
        repository.recordDownload("sample");
        byte[] replacement = {(byte) 0xfe, 0, 3, 4, 5};
        Scroll renamed = new Scroll("sample", "Renamed", "owner", scroll().uploadDate(), "new.bin");
        repository.save(renamed, replacement);
        var restarted = new FileScrollRepository(directory);
        assertEquals(List.of(renamed), restarted.findAll());
        assertArrayEquals(replacement, restarted.readContent("sample"));
        assertEquals(List.of(new ScrollStatistics("sample", 1, 1, false)), restarted.statistics());
        restarted.delete("sample");
        var afterDelete = new FileScrollRepository(directory);
        assertTrue(afterDelete.findAll().isEmpty());
        assertThrows(NoSuchElementException.class, () -> afterDelete.readContent("sample"));
        assertEquals(List.of(new ScrollStatistics("sample", 1, 1, true)), afterDelete.statistics());
        try (var files = Files.list(directory.resolve("files"))) {
            assertEquals(0, files.count());
        }
    }

    @Test void reportsMissingBinaryInsteadOfReturningEmptyContent() throws Exception {
        var repository = new FileScrollRepository(directory);
        repository.save(scroll(), new byte[]{1});
        try (var files = Files.list(directory.resolve("files"))) {
            for (Path file : files.toList()) Files.delete(file);
        }
        assertThrows(UncheckedIOException.class, () -> repository.readContent("sample"));
        assertThrows(IOException.class, () -> new FileScrollRepository(directory));
    }

    @Test void rejectsCorruptIndexRows() throws Exception {
        for (String row : List.of("wrong\tfield\tcount", "!\t!\t!\t!\t!")) {
            Files.writeString(directory.resolve("index.db"), row);
            assertThrows(IOException.class, () -> new FileScrollRepository(directory));
        }
    }

    @Test void rejectsCorruptStatisticsRows() throws Exception {
        for (String row : List.of("wrong\tcount", encode("sample") + "\t" + encode("not-a-number") + "\t" + encode("1"))) {
            Files.writeString(directory.resolve("stats.db"), row);
            assertThrows(IOException.class, () -> new FileScrollRepository(directory));
        }
    }

    @Test void loadsLegacyStatisticsAndSkipsBlankLines() throws Exception {
        Files.writeString(directory.resolve("index.db"), "\n  \n");
        Files.writeString(directory.resolve("stats.db"), "\n"
                + String.join("\t", encode("legacy"), encode("2"), encode("3")) + "\n\n"
                + String.join("\t", encode("removed"), encode("1"), encode("4"), encode("1")) + "\n");
        var repository = new FileScrollRepository(directory);
        assertTrue(repository.findAll().isEmpty());
        assertEquals(List.of(new ScrollStatistics("legacy", 2, 3, false),
                new ScrollStatistics("removed", 1, 4, true)), repository.statistics());
        repository.recordDownload("new-counter");
        assertTrue(new FileScrollRepository(directory).statistics()
                .contains(new ScrollStatistics("new-counter", 0, 1, false)));
    }

    @Test void reportsFailedBinaryWriteWithoutAddingMetadata() throws Exception {
        var repository = new FileScrollRepository(directory);
        Files.delete(directory.resolve("files"));
        Files.writeString(directory.resolve("files"), "not a directory");
        assertThrows(UncheckedIOException.class, () -> repository.save(scroll(), new byte[]{1}));
        assertTrue(repository.findById("sample").isEmpty());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
