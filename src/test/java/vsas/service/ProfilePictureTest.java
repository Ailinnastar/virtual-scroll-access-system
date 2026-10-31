package vsas.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfilePictureTest {
    private byte[] image(String format) throws Exception {
        var image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.BLUE);
            graphics.fillRect(0, 0, 8, 4);
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes));
        return bytes.toByteArray();
    }

    @Test void acceptsSupportedFormatsAndProducesSquarePngAvatars() throws Exception {
        for (String format : new String[]{"png", "jpeg", "gif"}) {
            String picture = ProfilePicture.encode(image(format));
            assertTrue(picture.startsWith("data:image/png;base64,"));
            byte[] bytes = Base64.getDecoder().decode(picture.substring(picture.indexOf(',') + 1));
            BufferedImage avatar = ImageIO.read(new ByteArrayInputStream(bytes));
            assertEquals(256, avatar.getWidth());
            assertEquals(256, avatar.getHeight());
            var center = new java.awt.Color(avatar.getRGB(128, 128));
            // JPEG is lossy; verify the colour survives without requiring identical RGB bytes.
            assertTrue(center.getBlue() > 245 && center.getRed() < 10 && center.getGreen() < 10);
        }
    }

    @Test void rejectsEmptyOversizedAndUnsupportedFiles() throws Exception {
        for (byte[] bytes : new byte[][]{null, new byte[0], new byte[ProfilePicture.MAX_BYTES + 1],
                "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8), image("bmp")}) {
            assertThrows(IllegalArgumentException.class, () -> ProfilePicture.encode(bytes));
        }
    }

    @Test void rejectsTruncatedImageWithAUsefulError() throws Exception {
        byte[] truncated = Arrays.copyOf(image("png"), 20);
        var error = assertThrows(IllegalArgumentException.class, () -> ProfilePicture.encode(truncated));
        assertTrue(error.getMessage().contains("Picture could not be read"));
    }
    @Test void rejectsExcessiveImageDimensionsBeforeDecodingPixels() throws Exception {
        var image = new BufferedImage(4001, 4000, BufferedImage.TYPE_BYTE_GRAY);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        assertTrue(output.size() < ProfilePicture.MAX_BYTES);
        var error = assertThrows(IllegalArgumentException.class, () -> ProfilePicture.encode(output.toByteArray()));
        assertTrue(error.getMessage().contains("16 million pixels"));
    }

}
