package vsas.service;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.model.User;
import vsas.model.UserType;
import vsas.model.ScrollFilter;
import vsas.repository.InMemoryScrollRepository;
import static org.junit.jupiter.api.Assertions.*;

class ScrollValidationTest {
    private final InMemoryScrollRepository repository = new InMemoryScrollRepository();
    private final LibraryScrollService service = new LibraryScrollService(repository);
    private final User owner = new User("owner", "owner", "hash", "Owner", "o@example.com", "0412345678", UserType.GENERAL);
    @TempDir Path directory;

    @Test void validatesNamesFilesAndIdentifiersAtBoundaries() {
        for (String name : new String[]{null, "", " ", " leading", "trailing ", "x".repeat(81), "bad\nname"}) {
            assertThrows(IllegalArgumentException.class, () -> service.upload(owner, name, null, "file.bin", new byte[]{1}));
        }
        for (byte[] bytes : new byte[][]{null, new byte[0], new byte[LibraryScrollService.MAX_BYTES + 1]}) {
            assertThrows(IllegalArgumentException.class, () -> service.upload(owner, "Valid", null, "file.bin", bytes));
        }
        for (String filename : new String[]{null, "", " ", ".", "..", "bad\nname"}) {
            var scroll = service.upload(owner, "Valid " + repository.findAll().size(), null, filename, new byte[]{1});
            assertEquals("scroll.bin", scroll.originalFileName());
        }
        var longName = service.upload(owner, "x".repeat(80), null, "x".repeat(130), new byte[]{1});
        assertEquals(120, longName.originalFileName().length());
        for (String id : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> service.preview(id));
        }
        assertThrows(NoSuchElementException.class, () -> service.preview("missing"));
        assertThrows(NoSuchElementException.class, () -> repository.readContent("missing"));
    }

    @Test void guestCannotMutateOrDownloadAndOnlyAdminCanRemoveAny() {
        User guest = new User("guest", "guest", "", "Guest", "", "", UserType.GUEST);
        var scroll = service.upload(owner, "Original", null, "file.bin", new byte[]{1});
        assertThrows(SecurityException.class, () -> service.downloadBytes(guest, scroll.id()));
        assertThrows(SecurityException.class, () -> service.upload(guest, "Guest", null, "file", new byte[]{1}));
        assertThrows(SecurityException.class, () -> service.remove(guest, scroll.id()));
        assertThrows(SecurityException.class, () -> service.update(guest, scroll.id(), "New", (Path) null));
        assertThrows(ForbiddenException.class, () -> service.removeAny(null, scroll.id()));
        assertThrows(ForbiddenException.class, () -> service.removeAny(owner, scroll.id()));
        User admin = new User("admin", "admin", "hash", "Admin", "", "", UserType.ADMIN);
        service.removeAny(admin, scroll.id());
        assertTrue(service.search(null).isEmpty());
    }

    @Test void replacementCanKeepNameAndRejectDuplicatesWithoutChangingFile() throws Exception {
        var first = service.upload(owner, "First", null, "first.bin", new byte[]{1});
        service.upload(owner, "Second", null, "second.bin", new byte[]{2});
        assertThrows(IllegalArgumentException.class, () -> service.update(owner, first.id(), "SECOND", null, null));
        var updated = service.update(owner, first.id(), " ", null, new byte[]{3, 4});
        assertEquals("First", updated.name());
        assertEquals("scroll.bin", updated.originalFileName());
        assertArrayEquals(new byte[]{3, 4}, service.downloadBytes(owner, first.id()));
        Path replacement = directory.resolve("replacement.txt");
        Files.writeString(replacement, "new content");
        assertEquals("replacement.txt", service.update(owner, first.id(), null, replacement).originalFileName());
        assertThrows(UncheckedIOException.class, () -> service.upload(owner, "Missing", directory.resolve("missing")));
        assertThrows(UncheckedIOException.class, () -> service.update(owner, first.id(), "Rename", directory.resolve("missing")));
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        assertThrows(UncheckedIOException.class, () -> service.download(owner, first.id(), blocked.resolve("download")));
        assertEquals(2, service.search(new ScrollFilter(" ", " ", " ", null)).size());
    }

    @Test void renamingPreservesFileContentsFilenameOwnerAndUploadDate() {
        byte[] content = "first line\nsecond line".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var original = service.upload(owner, "Original", null, "original.txt", content);
        var updated = service.update(owner, original.id(), "Renamed", (Path) null);
        assertEquals("Renamed", updated.name());
        assertEquals(original.originalFileName(), updated.originalFileName());
        assertEquals(original.uploadDate(), updated.uploadDate());
        assertEquals(original.uploaderId(), updated.uploaderId());
        assertArrayEquals(content, service.downloadBytes(owner, original.id()));
        assertEquals(1, service.search(null).size());
    }

    @Test void filenameWithParentTraversalMarkerFallsBackToSafeName() {
        var scroll = service.upload(owner, "Safe filename", null, "..", new byte[]{1});
        assertEquals("scroll.bin", scroll.originalFileName());
    }

    @Test void exhaustingActiveOrHistoricalIdsFailsWithoutSaving() {
        for (boolean historical : new boolean[]{false, true}) {
            int[] probes = {0};
            String[] lastId = {null};
            vsas.repository.ScrollRepository occupied = new vsas.repository.ScrollRepository() {
                public java.util.Optional<vsas.model.Scroll> findById(String id) {
                    probes[0]++;
                    lastId[0] = id;
                    return historical ? java.util.Optional.empty() : java.util.Optional.of(
                            new vsas.model.Scroll(id, "Existing", owner.idKey(), java.time.LocalDate.now(), "file.bin"));
                }
                public java.util.List<vsas.model.Scroll> findAll() { return java.util.List.of(); }
                public byte[] readContent(String id) { throw new AssertionError("Must not read content"); }
                public void save(vsas.model.Scroll scroll, byte[] bytes) { fail("Must not overwrite existing IDs"); }
                public void delete(String id) { fail("Must not delete an existing scroll"); }
                public void recordUpload(String id) { fail("Must not record a failed upload"); }
                public void recordDownload(String id) { fail("Must not record a download"); }
                public java.util.List<vsas.model.ScrollStatistics> statistics() {
                    return java.util.List.of(new vsas.model.ScrollStatistics(lastId[0], 1, 2, true));
                }
            };
            var exhausted = new LibraryScrollService(occupied);
            assertThrows(IllegalStateException.class, () -> exhausted.upload(owner, "New", null, "new.bin", new byte[]{1}));
            assertEquals(32, probes[0]);
        }
    }

    @Test void downloadedContentCannotMutateStoredBytes() {
        byte[] source = {1, 2, 3};
        var scroll = service.upload(owner, "Immutable", null, "file.bin", source);
        source[0] = 99;
        byte[] download = service.downloadBytes(owner, scroll.id());
        assertArrayEquals(new byte[]{1, 2, 3}, download);
        download[0] = 88;
        assertArrayEquals(new byte[]{1, 2, 3}, service.downloadBytes(owner, scroll.id()));
    }
}
