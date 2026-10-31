package vsas;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.model.*;
import vsas.repository.FileUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.UserService;
import vsas.ui.WebUi;
import static org.junit.jupiter.api.Assertions.*;

class PasswordRecoveryApiTest {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String base, String path, String method, String body, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String base, String username) throws Exception {
        return send(base, "/api/login", "POST", "username=" + username + "&password=old", null)
                .headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    @Test void adminRecoveryPersistsPasswordConsumesTokenAndRevokesEverySession() throws Exception {
        Path file = directory.resolve("users.db");
        var repository = new FileUserRepository(file);
        var hasher = new Pbkdf2PasswordHasher(10_000);
        repository.save(new User("admin", "admin", hasher.hash("old".toCharArray()),
                "Admin", "admin@example.com", "0412345678", UserType.ADMIN));
        var users = new UserService(repository, hasher);
        users.register("member", "member", "old".toCharArray(), "Member", "member@example.com", "0412345678");
        var ui = new WebUi(0, users);
        ui.start();
        try {
            String base = "http://localhost:" + ui.port();
            assertEquals(200, send(base, "/forgot-password.html", "GET", null, null).statusCode());
            String member = login(base, "member");
            String otherSession = login(base, "member");
            String admin = login(base, "admin");
            assertEquals(401, send(base, "/api/admin/password-recovery", "POST", "username=member", null).statusCode());
            assertEquals(403, send(base, "/api/admin/password-recovery", "POST", "username=member", member).statusCode());
            assertEquals(405, send(base, "/api/password/reset", "GET", null, null).statusCode());
            var issued = send(base, "/api/admin/password-recovery", "POST", "username=member", admin);
            assertEquals(200, issued.statusCode());
            assertEquals("no-store", issued.headers().firstValue("Cache-Control").orElseThrow());
            String token = issued.body().split("\"token\":\"")[1].split("\"")[0];
            String form = "token=" + token + "&password=new-secret&confirmPassword=wrong";
            assertEquals(400, send(base, "/api/password/reset", "POST", form, null).statusCode());
            form = "token=" + token + "&password=new-secret&confirmPassword=new-secret";
            assertEquals(200, send(base, "/api/password/reset", "POST", form, null).statusCode());
            assertEquals(400, send(base, "/api/password/reset", "POST", form, null).statusCode());
            assertEquals(401, send(base, "/api/session", "GET", null, member).statusCode());
            assertEquals(401, send(base, "/api/session", "GET", null, otherSession).statusCode());
            assertEquals(200, send(base, "/api/session", "GET", null, admin).statusCode());
            assertEquals(401, send(base, "/api/login", "POST", "username=member&password=old", null).statusCode());
            var restarted = new UserService(new FileUserRepository(file), hasher);
            assertEquals("member", restarted.login("member", "new-secret".toCharArray()).username());
            assertThrows(IllegalArgumentException.class, () -> restarted.recoverPassword(token, "another".toCharArray()));
        } finally { ui.stop(); }
    }
}
