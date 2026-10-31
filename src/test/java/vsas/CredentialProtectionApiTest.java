package vsas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.repository.FileUserRepository;
import vsas.repository.InMemoryScrollRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.LibraryAdminService;
import vsas.service.LibraryScrollService;
import vsas.service.UserService;
import vsas.ui.WebUi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CredentialProtectionApiTest {
    @TempDir Path directory;

    @Test void accountAndAdminResponsesDoNotExposeHashesOrAllowProfileHashInjection() throws Exception {
        FileUserRepository repository = new FileUserRepository(directory.resolve("users.db"));
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(10_000);
        UserService users = new UserService(repository, hasher);
        InMemoryScrollRepository scrollRepository = new InMemoryScrollRepository();
        LibraryScrollService scrolls = new LibraryScrollService(scrollRepository);
        LibraryAdminService admin = new LibraryAdminService(repository, hasher, scrollRepository, scrolls);
        admin.initialiseAdmin("maintainer", "Admin-Test-Password".toCharArray());
        WebUi ui = new WebUi(0, users, scrolls, admin);
        ui.start();
        try {
            String base = "http://localhost:" + ui.port();
            var registration = send(base, "/api/register", "POST", "idKey=evan-id&username=evan"
                    + "&password=Member-Test-Password&confirmPassword=Member-Test-Password"
                    + "&fullName=Evan&email=evan%40example.com"
                    + "&phone=0412345678&passwordHash=forged-hash", null);
            assertEquals(201, registration.statusCode(), registration.body());
            String hash = repository.findByUsername("evan").orElseThrow().passwordHash();
            String adminHash = repository.findByUsername("maintainer").orElseThrow().passwordHash();
            assertNotEquals("forged-hash", hash);
            assertNoCredentials(registration, hash, adminHash);

            var login = send(base, "/api/login", "POST",
                    "username=evan&password=Member-Test-Password", null);
            String memberCookie = cookie(login);
            assertNoCredentials(login, hash, adminHash);
            var session = send(base, "/api/session", "GET", "", memberCookie);
            assertEquals(200, session.statusCode());
            assertNoCredentials(session, hash, adminHash);

            var updated = send(base, "/api/profile", "POST",
                    "fullName=Evan+Updated&email=updated%40example.com&phone=0498765432"
                            + "&passwordHash=forged-hash&newPassword=Injected-Password&type=ADMIN",
                    memberCookie);
            assertEquals(200, updated.statusCode(), updated.body());
            assertNoCredentials(updated, hash, adminHash);
            assertEquals(hash, repository.findByUsername("evan").orElseThrow().passwordHash());
            assertTrue(updated.body().contains("\"type\":\"GENERAL\""));
            assertEquals(200, send(base, "/api/login", "POST",
                    "username=evan&password=Member-Test-Password", null).statusCode());

            var invalid = send(base, "/api/profile", "POST",
                    "fullName=Evan&email=invalid&phone=0412345678", memberCookie);
            assertEquals(400, invalid.statusCode());
            assertNoCredentials(invalid, hash, adminHash);
            assertEquals(hash, repository.findByUsername("evan").orElseThrow().passwordHash());

            String adminCookie = cookie(send(base, "/api/login", "POST",
                    "username=maintainer&password=Admin-Test-Password", null));
            var listed = send(base, "/api/admin/users", "GET", "", adminCookie);
            assertEquals(200, listed.statusCode());
            assertNoCredentials(listed, hash, adminHash);

            var created = send(base, "/api/admin/users", "POST",
                    "idKey=bob-id&username=bob&password=Another-Test-Password&fullName=Bob"
                            + "&email=bob%40example.com&phone=0412345678&type=GENERAL", adminCookie);
            assertEquals(201, created.statusCode(), created.body());
            assertNoCredentials(created, hash, adminHash,
                    repository.findByUsername("bob").orElseThrow().passwordHash());

            var denied = send(base, "/api/admin/users", "GET", "", memberCookie);
            assertEquals(403, denied.statusCode());
            assertNoCredentials(denied, hash, adminHash);
            for (String path : new String[]{"/users.db", "/data/users.db", "/web/users.db",
                    "/users.db123.tmp", "/../data/users.db", "/%2e%2e/data/users.db"}) {
                var download = send(base, path, "GET", "", adminCookie);
                assertTrue(download.statusCode() >= 400, path);
                assertNoCredentials(download, hash, adminHash);
            }
        } finally {
            ui.stop();
        }
    }

    private static void assertNoCredentials(HttpResponse<String> response, String... hashes) {
        assertFalse(response.body().contains("passwordHash"));
        assertFalse(response.body().contains("pbkdf2-sha256$"));
        assertFalse(response.body().contains("Member-Test-Password"));
        assertFalse(response.body().contains("Admin-Test-Password"));
        assertFalse(response.body().contains("Another-Test-Password"));
        for (String hash : hashes) {
            assertFalse(response.body().contains(hash), "Response leaked a stored hash");
        }
    }

    private static String cookie(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static HttpResponse<String> send(String base, String path, String method,
                                             String body, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (cookie != null) request.header("Cookie", cookie);
        if (method.equals("POST")) {
            request.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        }
        return HttpClient.newHttpClient().send(request.method(method,
                        HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
