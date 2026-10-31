package vsas.service;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScrollPreviewerTest {

    @Test
    void returnsFirstFiveLinesByDefault() {
        ScrollPreviewer previewer = new ScrollPreviewer();
        byte[] content = utf8(
                "line 1\nline 2\nline 3\nline 4\nline 5\nline 6"
        );

        assertEquals(
                "line 1\nline 2\nline 3\nline 4\nline 5",
                text(previewer.preview(content))
        );
    }

    @Test
    void supportsConfiguredLineLimit() {
        ScrollPreviewer previewer = new ScrollPreviewer(2);

        assertEquals(
                "first\nsecond",
                text(previewer.preview(utf8("first\nsecond\nthird")))
        );
    }

    @Test
    void returnsAllLinesWhenFileIsShort() {
        ScrollPreviewer previewer = new ScrollPreviewer();

        assertEquals(
                "first\nsecond",
                text(previewer.preview(utf8("first\nsecond")))
        );
    }

    @Test
    void returnsEmptyPreviewForEmptyFile() {
        ScrollPreviewer previewer = new ScrollPreviewer();

        assertArrayEquals(new byte[0], previewer.preview(new byte[0]));
    }

    @Test
    void rejectsInvalidUtf8Content() {
        ScrollPreviewer previewer = new ScrollPreviewer();
        byte[] invalidUtf8 = {(byte) 0xC3, (byte) 0x28};

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> previewer.preview(invalidUtf8)
        );

        assertTrue(exception.getMessage().contains("UTF-8 text"));
    }

    @Test
    void rejectsBinaryControlCharacters() {
        ScrollPreviewer previewer = new ScrollPreviewer();

        assertThrows(
                IllegalArgumentException.class,
                () -> previewer.preview(new byte[]{0, 1, 2})
        );
    }

    @Test
    void rejectsNullContent() {
        ScrollPreviewer previewer = new ScrollPreviewer();

        assertThrows(
                NullPointerException.class,
                () -> previewer.preview(null)
        );
    }

    @Test
    void rejectsInvalidLineLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ScrollPreviewer(0)
        );
    }

    @Test
    void preservesTabsAndNormalisesWindowsLineEndings() {
        ScrollPreviewer previewer = new ScrollPreviewer();
        assertEquals("name\tvalue\nnext\tentry", text(previewer.preview(utf8("name\tvalue\r\nnext\tentry\r\n"))));
        assertEquals("first\nsecond", text(previewer.preview(utf8("first\rsecond"))));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}