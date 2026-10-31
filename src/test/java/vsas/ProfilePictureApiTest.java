package vsas;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.repository.FileUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.UserService;
import vsas.ui.WebUi;
import static org.junit.jupiter.api.Assertions.*;

class ProfilePictureApiTest {
    @TempDir Path directory;

    @Test void uploadsReplacesPersistsAndIsolatesPictures() throws Exception {
        Path database = directory.resolve("users.db");
        var users = new UserService(new FileUserRepository(database), new Pbkdf2PasswordHasher(10_000));
        users.register("alice", "alice", "secret".toCharArray(), "Alice", "a@example.com", "0412345678");
        users.register("bob", "bob", "secret".toCharArray(), "Bob", "b@example.com", "0412345678");
        var ui = new WebUi(0, users);
        ui.start();
        try {
            String base = "http://localhost:" + ui.port();
            var client = HttpClient.newHttpClient();
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=alice&password=secret")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            URI upload = URI.create(base + "/api/profile/picture");
            assertEquals(401, client.send(HttpRequest.newBuilder(upload)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(image(0xff0000))).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(upload).header("Cookie", cookie).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            String first = null;
            for (int color : new int[]{0xff0000, 0x0000ff}) {
                var response = client.send(HttpRequest.newBuilder(upload).header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(image(color))).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("data:image/png;base64,"));
                if (first != null) assertNotEquals(first, response.body());
                first = response.body();
            }
            for (byte[] invalid : new byte[][]{{}, "not an image".getBytes(), new byte[2 * 1024 * 1024 + 1]}) {
                assertEquals(400, client.send(HttpRequest.newBuilder(upload).header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(invalid)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var session = client.send(HttpRequest.newBuilder(URI.create(base + "/api/session"))
                    .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(first, session.body());
            var reloaded = new FileUserRepository(database);
            assertFalse(reloaded.findById("alice").orElseThrow().picture().isEmpty());
            assertTrue(reloaded.findById("bob").orElseThrow().picture().isEmpty());
        } finally { ui.stop(); }
    }

    @Test void loadsLegacyAccountsWithoutPictures() throws Exception {
        Path database = directory.resolve("legacy.db");
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String[] fields = {"id", "legacy", "hash", "Legacy", "l@example.com", "0412345678", "GENERAL"};
        Files.writeString(database, java.util.Arrays.stream(fields)
                .map(value -> encoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("\t")));
        assertEquals("", new FileUserRepository(database).findById("id").orElseThrow().picture());
    }

    private byte[] image(int color) throws Exception {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, color);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
