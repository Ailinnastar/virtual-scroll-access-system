package vsas.service;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Validates uploads and stores a small, metadata-free PNG avatar. */
public final class ProfilePicture {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private ProfilePicture() { }

    public static String encode(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_BYTES) {
            throw new IllegalArgumentException("Choose a picture of 2 MB or smaller");
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Choose a PNG, JPEG or GIF image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!java.util.Set.of("png", "jpeg", "gif").contains(format)) {
                    throw new IllegalArgumentException("Choose a PNG, JPEG or GIF image");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 16_000_000) {
                    throw new IllegalArgumentException("Picture must contain at most 16 million pixels");
                }
                BufferedImage original = reader.read(0);
                BufferedImage thumbnail = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                var graphics = thumbnail.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    int side = Math.min(width, height);
                    int x = (width - side) / 2;
                    int y = (height - side) / 2;
                    graphics.drawImage(original, 0, 0, 256, 256, x, y, x + side, y + side, null);
                } finally {
                    graphics.dispose();
                }
                var output = new ByteArrayOutputStream();
                ImageIO.write(thumbnail, "png", output);
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Picture could not be read; choose another image", error);
        }
    }
}
