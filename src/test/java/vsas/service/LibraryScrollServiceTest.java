package vsas.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.model.Scroll;
import vsas.model.ScrollFilter;
import vsas.model.User;
import vsas.model.UserType;
import vsas.repository.FileScrollRepository;
import vsas.repository.InMemoryScrollRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;


class LibraryScrollServiceTest {
    private final User owner = member("Ailinna", "ailinna");
    private final User other = member("Xiangbin", "xiangbin");

    @Test void uploadsListsDownloadsAndLetsOnlyTheOwnerDelete() throws Exception {
        LibraryScrollService library = new LibraryScrollService(new InMemoryScrollRepository());
        byte[] content = "First line\nSecond line\nThird\n".getBytes(StandardCharsets.UTF_8);
        Scroll uploaded = library.upload(owner, "Agile Notes", "SCR-AILINNA", "notes.txt", content);

        assertTrue(uploaded.id().matches("SCR-[A-Z0-9]{8}"));
        assertNotEquals("SCR-AILINNA", uploaded.id());
        assertEquals(owner.idKey(), uploaded.uploaderId());
        assertEquals(LocalDate.now(), uploaded.uploadDate());
        assertEquals(1, library.search(null).size());
        assertEquals("First line\nSecond line\nThird", new String(library.preview(uploaded.id()), StandardCharsets.UTF_8));

        assertArrayEquals(content, library.downloadBytes(owner, uploaded.id()));
        assertEquals(1, library.downloadCount(uploaded.id()));
        assertThrows(SecurityException.class, () -> library.downloadBytes(null, uploaded.id()));
        assertThrows(SecurityException.class, () -> library.upload(null, "Guest", null, "a.txt", content));
        assertThrows(ForbiddenException.class, () -> library.remove(other, uploaded.id()));
        assertEquals(1, library.search(new ScrollFilter(null, null, "agile", null)).size());

        library.remove(owner, uploaded.id());
        assertTrue(library.search(null).isEmpty());
        assertEquals(1, library.downloadCount(uploaded.id()));
        assertTrue(library.statistics().stream().anyMatch(row ->
                row.scrollId().equals(uploaded.id()) && row.removed()));
        Scroll replacement = library.upload(owner, "Another", "SCR-AILINNA", "notes.txt", content);
        assertNotEquals(uploaded.id(), replacement.id());
        assertNotEquals("SCR-AILINNA", replacement.id());
    }

    @Test void rejectsDuplicateNamesAndAssignsTheNextId(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("scroll.txt");
        Files.writeString(file, "alpha\nbeta");
        FileScrollRepository repository = new FileScrollRepository(directory.resolve("scrolls"));
        LibraryScrollService library = new LibraryScrollService(repository);
        Scroll first = library.upload(owner, "Scroll One", file);
        assertTrue(first.id().matches("SCR-[A-Z0-9]{8}"));
        assertThrows(IllegalArgumentException.class, () -> library.upload(owner, "scroll one", null, "b.txt",
                "more".getBytes(StandardCharsets.UTF_8)));

        Path destination = directory.resolve("out").resolve("saved.txt");
        library.download(other, first.id(), destination);
        assertEquals("alpha\nbeta", Files.readString(destination));

        LibraryScrollService reloaded = new LibraryScrollService(new FileScrollRepository(directory.resolve("scrolls")));
        assertEquals("alpha\nbeta", new String(reloaded.downloadBytes(owner, first.id()), StandardCharsets.UTF_8));
        reloaded.remove(owner, first.id());
        LibraryScrollService afterDelete = new LibraryScrollService(new FileScrollRepository(directory.resolve("scrolls")));
        assertTrue(afterDelete.search(null).isEmpty());
        assertEquals(2, afterDelete.downloadCount(first.id()));
        assertTrue(afterDelete.statistics().stream().anyMatch(row ->
                row.scrollId().equals(first.id()) && row.removed()));
        Scroll replacement = afterDelete.upload(owner, "Scroll Two", null, "c.txt",
                new byte[]{0, 1, 2, 3});
        assertTrue(replacement.id().matches("SCR-[A-Z0-9]{8}"));
        assertNotEquals(first.id(), replacement.id());
        assertEquals("Binary scroll (4 bytes)", new String(afterDelete.preview(replacement.id()), StandardCharsets.UTF_8));
        assertEquals(2, afterDelete.downloadCount(first.id()));
    }

    @Test void filtersByUploaderIdScrollIdAndDate() {
        LibraryScrollService library = new LibraryScrollService(new InMemoryScrollRepository());
        library.upload(owner, "Owned", null, "a.txt", "one".getBytes(StandardCharsets.UTF_8));
        Scroll shared = library.upload(other, "Shared", "OTHER-1", "b.txt", "two".getBytes(StandardCharsets.UTF_8));
        assertNotEquals("OTHER-1", shared.id());
        assertEquals(1, library.search(new ScrollFilter(owner.idKey(), null, null, null)).size());
        assertEquals("Shared", library.search(new ScrollFilter(null, shared.id(), null, null)).get(0).name());
        assertEquals(2, library.search(new ScrollFilter(null, null, null, LocalDate.now())).size());
        assertTrue(library.search(new ScrollFilter(null, null, null, LocalDate.now().minusDays(1))).isEmpty());
    }

    @Test
    void combinesFiltersAndReturnsEmptyWhenNothingMatches() {
        LibraryScrollService library =
                new LibraryScrollService(new InMemoryScrollRepository());

        Scroll target = library.upload(
                owner, "Agile Notes", null, "notes.txt",
                "first content".getBytes(StandardCharsets.UTF_8)
        );

        library.upload(
                other, "Agile Guide", null, "guide.txt",
                "other content".getBytes(StandardCharsets.UTF_8)
        );

        // All four conditions must match.
        var matching = library.search(new ScrollFilter(
                owner.idKey(), target.id(), "AGILE", target.uploadDate()
        ));

        assertEquals(1, matching.size());
        assertEquals(target.id(), matching.get(0).id());

        // A wrong uploader must produce an empty result.
        assertTrue(library.search(new ScrollFilter(
                other.idKey(), target.id(), "AGILE", target.uploadDate()
        )).isEmpty());

        // A wrong scroll ID must produce an empty result.
        assertTrue(library.search(new ScrollFilter(
                owner.idKey(), "SCR-MISSING", "AGILE", target.uploadDate()
        )).isEmpty());

        // A wrong name must produce an empty result.
        assertTrue(library.search(new ScrollFilter(
                owner.idKey(), target.id(), "unmatched", target.uploadDate()
        )).isEmpty());

        // A wrong upload date must produce an empty result.
        assertTrue(library.search(new ScrollFilter(
                owner.idKey(), target.id(), "AGILE",
                target.uploadDate().plusDays(1)
        )).isEmpty());
    }

    @Test
    void ownerCanRenameAndReplaceUploadedScroll(@TempDir Path directory) throws Exception {
        InMemoryScrollRepository repository = new InMemoryScrollRepository();
        LibraryScrollService library = new LibraryScrollService(repository);

        Scroll uploaded = library.upload(
                owner,
                "Old Name",
                null,
                "old.txt",
                "old content".getBytes(StandardCharsets.UTF_8)
        );

        Path replacement = directory.resolve("new.txt");
        Files.writeString(replacement, "new content");

        Scroll updated = library.update(
                owner,
                uploaded.id(),
                "New Name",
                replacement
        );

        assertEquals(uploaded.id(), updated.id());
        assertEquals("New Name", updated.name());
        assertEquals(owner.idKey(), updated.uploaderId());
        assertEquals(uploaded.uploadDate(), updated.uploadDate());
        assertEquals("new.txt", updated.originalFileName());
        assertEquals(
                "new content",
                new String(repository.readContent(updated.id()), StandardCharsets.UTF_8)
        );
    }

    @Test
    void otherUsersCannotEditUploadedScroll() {
        InMemoryScrollRepository repository = new InMemoryScrollRepository();
        LibraryScrollService library = new LibraryScrollService(repository);

        Scroll uploaded = library.upload(
                owner,
                "Owner Notes",
                null,
                "notes.txt",
                "original content".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(
                ForbiddenException.class,
                () -> library.update(other, uploaded.id(), "Changed Name", null)
        );

        Scroll unchanged = repository.findById(uploaded.id()).orElseThrow();
        assertEquals("Owner Notes", unchanged.name());
        assertEquals(
                "original content",
                new String(repository.readContent(uploaded.id()), StandardCharsets.UTF_8)
        );
    }

    @Test
    void updatedScrollNameMustRemainUnique() {
        InMemoryScrollRepository repository = new InMemoryScrollRepository();
        LibraryScrollService library = new LibraryScrollService(repository);

        Scroll first = library.upload(
                owner,
                "First Scroll",
                null,
                "first.txt",
                "first".getBytes(StandardCharsets.UTF_8)
        );

        library.upload(
                other,
                "Second Scroll",
                null,
                "second.txt",
                "second".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> library.update(owner, first.id(), "second scroll", null)
        );

        assertEquals(
                "First Scroll",
                repository.findById(first.id()).orElseThrow().name()
        );
    }

    private static User member(String name, String id) {
        return new User(id, id, "hash", name, id + "@example.com", "0412345678", UserType.GENERAL);
    }
}
