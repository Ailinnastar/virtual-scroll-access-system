package vsas.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ScrollPreviewer {
    private static final int DEFAULT_MAX_LINES = 5;

    private final int maxLines;

    public ScrollPreviewer() {
        this(DEFAULT_MAX_LINES);
    }

    ScrollPreviewer(int maxLines) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("Maximum lines must be positive");
        }
        this.maxLines = maxLines;
    }

    public byte[] preview(byte[] content) {
        Objects.requireNonNull(content, "Scroll content is required");

        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "Preview is only available for UTF-8 text scrolls",
                    exception
            );
        }

        boolean containsBinaryControlCharacters = text.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && character != '\n'
                        && character != '\r'
                        && character != '\t'
        );

        if (containsBinaryControlCharacters) {
            throw new IllegalArgumentException(
                    "Preview is only available for UTF-8 text scrolls"
            );
        }

        String preview = text.lines()
                .limit(maxLines)
                .collect(Collectors.joining("\n"));

        return preview.getBytes(StandardCharsets.UTF_8);
    }
}