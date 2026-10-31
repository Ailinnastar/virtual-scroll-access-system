package vsas;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.repository.FileUserRepository;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path directory;

    @Test void configuredStartupServesHealthAndPreservesAccountsAcrossRestarts() throws Exception {
        var config = Map.of("VSAS_PORT", "0", "VSAS_DATA_DIR", directory.toString(),
                "VSAS_ADMIN_USERNAME", "librarian", "VSAS_ADMIN_PASSWORD", "initial-secret");
        var client = HttpClient.newHttpClient();
        var first = Main.start(config);
        try {
            String base = "http://localhost:" + first.port();
            var health = client.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals("OK", health.body());
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=librarian&password=initial-secret")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            assertTrue(login.body().contains("ADMIN"));
        } finally { first.stop(); }
        var second = Main.start(config);
        try {
            assertEquals(1, new FileUserRepository(directory.resolve("users.db")).findAll().size());
        } finally { second.stop(); }
    }

    @Test void invalidPortFailsBeforeCreatingCredentialStorage() {
        assertThrows(NumberFormatException.class, () -> Main.start(Map.of("VSAS_PORT", "invalid", "VSAS_DATA_DIR", directory.toString())));
        assertFalse(java.nio.file.Files.exists(directory.resolve("users.db")));
    }

    @Test void productionRejectsMissingAdminSecretBeforeWritingData() {
        assertThrows(IllegalArgumentException.class, () -> Main.start(Map.of(
                "VSAS_PRODUCTION", "true", "VSAS_DATA_DIR", directory.toString())));
        assertFalse(java.nio.file.Files.exists(directory.resolve("users.db")));
    }

    @Test void hostedPortAndSecureCookieConfigurationWork() throws Exception {
        var config = Map.of("PORT", "0", "VSAS_DATA_DIR", directory.toString(),
                "VSAS_PRODUCTION", "true", "VSAS_SECURE_COOKIES", "true",
                "VSAS_ADMIN_PASSWORD", "synthetic-private-admin-test-secret");
        var ui = Main.start(config);
        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + ui.port() + "/api/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=synthetic-private-admin-test-secret"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("; Secure"));
        } finally { ui.stop(); }
    }
}
