package vsas.service;

import org.junit.jupiter.api.Test;
import vsas.model.ScrollStatistics;
import vsas.model.User;
import vsas.model.UserType;
import vsas.repository.InMemoryScrollRepository;
import vsas.repository.InMemoryUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.ui.WebUi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class LibraryAdminServiceTest {
    @Test void generatedAdminUserIdsShareTheRegistrationSequence() {
        var accounts = new InMemoryUserRepository();
        var files = new InMemoryScrollRepository();
        var hasher = new Pbkdf2PasswordHasher(10_000);
        var users = new UserService(accounts, hasher);
        var admin = new LibraryAdminService(accounts, hasher, files, new LibraryScrollService(files));
        admin.initialiseAdmin("admin", "secret".toCharArray());
        var actor = accounts.findByUsername("admin").orElseThrow();
        var first = users.register(null, "first", "secret".toCharArray(),
                "First", "first@example.com", "0412345678");
        var second = admin.addUser(actor, "second", "secret".toCharArray(),
                "Second", "second@example.com", "0412345678", UserType.ADMIN);
        var third = users.register(null, "third", "secret".toCharArray(),
                "Third", "third@example.com", "0412345678");
        assertEquals("USR-00000001", first.idKey());
        assertEquals("USR-00000002", second.idKey());
        assertEquals("USR-00000003", third.idKey());
    }

    @Test void administratorManagesUsersAndSeesRemovedScrollStatistics() throws Exception {
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        InMemoryScrollRepository scrollRepository = new InMemoryScrollRepository();
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
        UserService users = new UserService(userRepository, hasher);
        LibraryScrollService scrolls = new LibraryScrollService(scrollRepository);
        LibraryAdminService admin = new LibraryAdminService(userRepository, hasher, scrollRepository, scrolls);
        admin.initialiseAdmin("admin", "Admin#2412".toCharArray());
        admin.initialiseAdmin("admin", "Another#9999".toCharArray());
        User administrator = users.login("admin", "Admin#2412".toCharArray());
        User member = users.register("member-1", "member", "password1".toCharArray(),
                "Member One", "member@example.com", "0411111111");

        assertEquals(2, admin.listUsers(administrator).size());
        assertThrows(ForbiddenException.class, () -> admin.listUsers(member));
        User extra = admin.addUser(administrator, "extra-1", "extra", "password2".toCharArray(),
                "Extra User", "extra@example.com", "0422222222", UserType.GENERAL);
        assertFalse(extra.passwordHash().isBlank());
        admin.deleteUser(administrator, extra.idKey());
        assertThrows(IllegalArgumentException.class, () -> admin.deleteUser(administrator, administrator.idKey()));

        var uploaded = scrolls.upload(member, "Notes", null, "notes.txt",
                "hello".getBytes(StandardCharsets.UTF_8));
        scrolls.downloadBytes(member, uploaded.id());
        scrolls.remove(member, uploaded.id());
        assertTrue(scrolls.search(null).isEmpty());
        ScrollStatistics archived = admin.statistics(administrator).stream()
                .filter(row -> row.scrollId().equals(uploaded.id())).findFirst().orElseThrow();
        assertTrue(archived.removed());
        assertEquals(1, archived.downloads());
        assertThrows(ForbiddenException.class, () -> admin.statistics(member));

        WebUi ui = new WebUi(0, users, scrolls, admin);
        ui.start();
        try {
            String base = "http://localhost:" + ui.port();
            String cookie = login(base);
            var listed = send(base, "/api/admin/users", "GET", null, "", cookie);
            assertEquals(200, listed.statusCode());
            assertTrue(listed.body().contains("\"username\":\"member\""));
            assertFalse(listed.body().contains("passwordHash"));
            assertEquals(401, send(base, "/api/admin/statistics", "GET", null, "", null).statusCode());
            assertEquals(403, send(base, "/api/admin/users", "GET", null, "", cookieOfMember(base)).statusCode());
            var stats = send(base, "/api/admin/statistics", "GET", null, "", cookie);
            assertEquals(200, stats.statusCode());
            assertTrue(stats.body().contains(uploaded.id()));
            assertTrue(stats.body().contains("\"removed\":true"));
        } finally {
            ui.stop();
        }
    }

    private static String login(String base) throws Exception {
        var response = send(base, "/api/login", "POST", "application/x-www-form-urlencoded",
                "username=admin&password=Admin%232412", null);
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static String cookieOfMember(String base) throws Exception {
        var response = send(base, "/api/login", "POST", "application/x-www-form-urlencoded",
                "username=member&password=password1", null);
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static HttpResponse<String> send(String base, String path, String method, String type,
                                             String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(java.time.Duration.ofSeconds(5));
        if (type != null) request.header("Content-Type", type);
        if (cookie != null) request.header("Cookie", cookie);
        return HttpClient.newHttpClient().send(request.method(method,
                        HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
