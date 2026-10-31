package vsas;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import vsas.model.Scroll;
import vsas.model.User;
import vsas.repository.InMemoryScrollRepository;
import vsas.repository.InMemoryUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.LibraryAdminService;
import vsas.service.LibraryScrollService;
import vsas.service.UserService;
import vsas.ui.WebUi;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP-level regression tests for permissions, failures and response encoding. */
class WebUiAdditionalTest {
    private static class Server implements AutoCloseable {
        final InMemoryUserRepository accounts = new InMemoryUserRepository();
        final InMemoryScrollRepository files = new InMemoryScrollRepository();
        final Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(10_000);
        final UserService users = new UserService(accounts, hasher);
        final LibraryScrollService scrolls = new LibraryScrollService(files);
        final LibraryAdminService admin = new LibraryAdminService(accounts, hasher, files, scrolls);
        final HttpClient client = HttpClient.newHttpClient();
        final WebUi ui;
        final User member;
        Server() throws Exception { this(true); }
        Server(boolean withAdmin) throws Exception {
            admin.initialiseAdmin("admin", "secret".toCharArray());
            member = users.register("member", "member", "secret".toCharArray(),
                    "Member", "member@example.com", "0412345678");
            ui = new WebUi(0, users, scrolls, withAdmin ? admin : null);
            ui.start();
        }
        HttpResponse<String> send(String path, String method, String body, String cookie, String contentType) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + path))
                    .timeout(Duration.ofSeconds(5));
            if (cookie != null) builder.header("Cookie", cookie);
            if (contentType != null) builder.header("Content-Type", contentType);
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> request(String path, String method, String body, String cookie) throws Exception {
            return send(path, method, body, cookie, "application/x-www-form-urlencoded");
        }
        String login(String username) throws Exception {
            var response = request("/api/login", "POST", "username=" + username + "&password=secret", null);
            assertEquals(200, response.statusCode());
            return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        }
        String createUser(String username, String type) {
            return "idKey=" + username + "&username=" + username + "&password=secret&fullName=New+User"
                    + "&email=new%40example.com&phone=0412345678&type=" + type;
        }
        public void close() { ui.stop(); }
    }

    @Test void accountEndpointsRejectWrongMethodsAndEmptyForms() throws Exception {
        try (var server = new Server()) {
            for (String path : new String[]{"/api/register", "/api/login", "/api/logout", "/api/admin/password-recovery"}) {
                assertEquals(405, server.request(path, "GET", "", null).statusCode(), path);
            }
            assertEquals(405, server.request("/api/session", "POST", "", null).statusCode());
            assertEquals(401, server.request("/api/login", "POST", "", null).statusCode());
            assertEquals(400, server.request("/api/register", "POST", "", null).statusCode());
            assertEquals(400, server.request("/api/password/reset", "POST", "password=new", null).statusCode());
        }
    }

    @Test void renameOnlyMultipartEditRetainsOriginalFileAndPreview() throws Exception {
        try (var server = new Server()) {
            var scroll = server.scrolls.upload(server.member, "Original", null, "original.txt", "first\nsecond".getBytes(StandardCharsets.UTF_8));
            String cookie = server.login("member");
            String body = "--edit\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nRenamed\r\n--edit--\r\n";
            var response = server.send("/api/scrolls/" + scroll.id(), "PUT", body, cookie, "multipart/form-data; boundary=edit");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Renamed"));
            assertTrue(response.body().contains("original.txt"));
            assertEquals("first\nsecond", server.request("/api/scrolls/" + scroll.id() + "/preview", "GET", "", null).body());
        }
    }

    @Test void binaryDownloadsUseSafeFallbackNamesAndActionsRequireGet() throws Exception {
        try (var server = new Server()) {
            String cookie = server.login("member");
            int index = 0;
            for (String filename : new String[]{null, "", ".", ".."}) {
                String id = "safe-" + index++;
                server.files.save(new Scroll(id, "Name " + id, server.member.idKey(), LocalDate.now(), filename), new byte[]{1});
                var download = server.request("/api/scrolls/" + id + "/download", "GET", "", cookie);
                assertEquals(200, download.statusCode());
                assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains(id + ".bin"));
                for (String action : new String[]{"download", "preview"}) {
                    assertEquals(405, server.request("/api/scrolls/" + id + "/" + action, "POST", "", cookie).statusCode());
                }
            }
        }
    }

    @Test void malformedMultipartRequestsNeverCreateScrolls() throws Exception {
        try (var server = new Server()) {
            String cookie = server.login("member");
            String[] malformed = {
                "", "--b", "--b--\r\n",
                "--b\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nOnly a name\r\n--b--",
                "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.bin\"\r\n\r\nx\r\n--b--",
                "--b\r\nBroken header\r\n--b--",
                "--bContent-Disposition: form-data; name=\"name\"\r\n\r\nName--b--",
                "--b\r\nContent-Type: text/plain\r\nContent-Disposition: form-data; name=\"unterminated\r\n\r\nName\r\n--b--"
            };
            for (String body : malformed) {
                assertEquals(400, server.send("/api/scrolls", "POST", body, cookie, "multipart/form-data; boundary=b").statusCode(), body);
                assertTrue(server.files.findAll().isEmpty());
            }
            assertEquals(400, server.send("/api/scrolls", "POST", "", cookie, null).statusCode());
        }
    }

    @Test void administratorsCreateListAndDeleteUsersWithoutExposingHashes() throws Exception {
        try (var server = new Server()) {
            String admin = server.login("admin");
            String member = server.login("member");
            assertEquals(401, server.request("/api/admin/users", "GET", "", null).statusCode());
            assertEquals(403, server.request("/api/admin/users", "GET", "", member).statusCode());
            int nextId = 1;
            for (String type : new String[]{"GENERAL", "ADMIN"}) {
                var created = server.request("/api/admin/users", "POST", server.createUser("new-" + type.toLowerCase(), type).replaceFirst("idKey=[^&]*&", ""), admin);
                assertEquals(201, created.statusCode());
                assertTrue(created.body().contains("\"idKey\":\"USR-0000000" + nextId++ + "\""));
                assertTrue(created.body().contains("\"type\":\"" + type + "\""));
                assertFalse(created.body().contains("passwordHash"));
                assertFalse(created.body().contains("pbkdf2-sha256"));
            }
            var listed = server.request("/api/admin/users", "GET", "", admin);
            assertTrue(listed.body().contains("\"username\":\"new-general\""));
            assertEquals(400, server.request("/api/admin/users", "POST", server.createUser("bad", "UNKNOWN"), admin).statusCode());
            assertEquals(400, server.request("/api/admin/users", "POST", server.createUser("bad", "GUEST"), admin).statusCode());
            assertEquals(400, server.request("/api/admin/users", "POST", server.createUser("new-general", "GENERAL"), admin).statusCode());
            var wrongMethod = server.request("/api/admin/users", "PUT", "", admin);
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("GET, POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
            assertEquals(405, server.request("/api/admin/users/USR-00000001", "GET", "", admin).statusCode());
            assertEquals(401, server.request("/api/admin/users/USR-00000001", "DELETE", "", null).statusCode());
            assertEquals(403, server.request("/api/admin/users/USR-00000001", "DELETE", "", member).statusCode());
            assertEquals(400, server.request("/api/admin/users/admin", "DELETE", "", admin).statusCode());
            assertEquals(200, server.request("/api/admin/users/USR-00000001", "DELETE", "", admin).statusCode());
            assertTrue(server.accounts.findById("USR-00000001").isEmpty());
            assertEquals(400, server.request("/api/admin/users/USR-00000001", "DELETE", "", admin).statusCode());
        }
    }

    @Test void adminScrollDeletionPreservesStatisticsAndChecksPermissions() throws Exception {
        try (var server = new Server()) {
            String admin = server.login("admin");
            String member = server.login("member");
            var first = server.scrolls.upload(server.member, "First", "first", "first.txt", "one".getBytes());
            var second = server.scrolls.upload(server.member, "Second", "second", "second.txt", "two".getBytes());
            server.scrolls.downloadBytes(server.member, first.id());
            String endpoint = "/api/admin/scrolls/" + first.id();
            assertEquals(405, server.request(endpoint, "GET", "", admin).statusCode());
            assertEquals(401, server.request(endpoint, "DELETE", "", null).statusCode());
            assertEquals(403, server.request(endpoint, "DELETE", "", member).statusCode());
            assertEquals(200, server.request(endpoint, "DELETE", "", admin).statusCode());
            assertEquals(404, server.request("/api/scrolls/" + first.id(), "GET", "", member).statusCode());
            assertEquals(405, server.request("/api/admin/statistics", "POST", "", admin).statusCode());
            assertEquals(401, server.request("/api/admin/statistics", "GET", "", null).statusCode());
            assertEquals(403, server.request("/api/admin/statistics", "GET", "", member).statusCode());
            var stats = server.request("/api/admin/statistics", "GET", "", admin);
            assertEquals(200, stats.statusCode());
            assertTrue(stats.body().contains("\"downloads\":1,\"removed\":true"), stats.body());
            assertTrue(stats.body().contains(second.id()));
        }
    }

    @Test void mineFilteringUsesSessionIdentityAndRejectsInvalidDates() throws Exception {
        try (var server = new Server()) {
            String cookie = server.login("member");
            server.scrolls.upload(server.member, "My Notes", "mine", "mine.txt", "mine".getBytes());
            var admin = server.accounts.findByUsername("admin").orElseThrow();
            server.scrolls.upload(admin, "Admin Notes", "other", "other.txt", "other".getBytes());
            assertEquals(401, server.request("/api/scrolls?mine=1", "GET", "", null).statusCode());
            var mine = server.request("/api/scrolls?mine=1&uploaderId=admin", "GET", "", cookie);
            assertEquals(200, mine.statusCode());
            assertTrue(mine.body().contains("My Notes"));
            assertFalse(mine.body().contains("Admin Notes"));
            var invalid = server.request("/api/scrolls?uploadDate=not-a-date", "GET", "", null);
            assertEquals(400, invalid.statusCode());
            assertTrue(invalid.body().contains("yyyy-MM-dd"));
            assertEquals(200, server.request("/api/scrolls?uploadDate=", "GET", "", cookie).statusCode());
        }
    }

    @Test void scrollEndpointsRejectInvalidMethodsMissingItemsAndMalformedUploads() throws Exception {
        try (var server = new Server()) {
            String cookie = server.login("member");
            assertEquals(405, server.request("/api/scrolls", "PATCH", "", cookie).statusCode());
            for (String path : new String[]{"/api/scrolls/missing", "/api/scrolls/missing/download", "/api/scrolls/missing/preview"}) {
                assertEquals(404, server.request(path, "GET", "", cookie).statusCode());
            }
            for (String path : new String[]{"/api/scrolls/", "/api/scrolls/bad%5Cname", "/api/scrolls/bad..name"}) {
                assertEquals(400, server.request(path, "GET", "", cookie).statusCode());
            }
            assertEquals(401, server.request("/api/scrolls/missing", "DELETE", "", null).statusCode());
            assertEquals(401, server.request("/api/scrolls/missing", "PUT", "", null).statusCode());
            assertEquals(405, server.request("/api/scrolls/missing", "PATCH", "", cookie).statusCode());
            assertEquals(405, server.request("/api/scrolls/missing/unknown", "POST", "", cookie).statusCode());
            assertEquals(400, server.send("/api/scrolls", "POST", "", cookie, "multipart/form-data").statusCode());
            assertEquals(400, server.send("/api/scrolls", "POST", "--b--\r\n", cookie, "multipart/form-data; boundary=b").statusCode());
            assertEquals(400, server.send("/api/scrolls", "POST", "--b\r\nbad headers\r\n--b--", cookie, "multipart/form-data; boundary=b").statusCode());
            assertEquals(400, server.send("/api/scrolls/missing", "PUT", "--b--\r\n", cookie, "multipart/form-data; boundary=b").statusCode());
        }
    }

    @Test void responsesEscapeUserContentAndCookiesCanContainOtherValues() throws Exception {
        try (var server = new Server()) {
            String cookie = server.login("member");
            String name = "Name \"quoted\"\\line\nnext\rreturn\ttab\u0001";
            String form = "fullName=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&email=member%40example.com&phone=0412345678";
            var updated = server.request("/api/profile", "POST", form, "other=value; " + cookie + "; trailing=x");
            assertEquals(200, updated.statusCode());
            assertTrue(updated.body().contains("\\\"quoted\\\"\\\\line\\nnext\\rreturn\\ttab\\u0001"), updated.body());
            assertEquals(401, server.request("/api/session", "GET", "", "unrelated=x; malformed; vsas_session=unknown").statusCode());
            server.files.save(new Scroll("unnamed", "Unnamed", server.member.idKey(), LocalDate.now(), ".."), "file".getBytes());
            var downloaded = server.request("/api/scrolls/unnamed/download", "GET", "", cookie);
            assertEquals(200, downloaded.statusCode());
            assertEquals("attachment; filename=\"unnamed.bin\"", downloaded.headers().firstValue("Content-Disposition").orElseThrow());
        }
    }
    @Test void oversizedFormsAndUnsupportedContentTypesAreRejected() throws Exception {
        try (var server = new Server()) {
            assertEquals(400, server.request("/api/register", "POST", "x=" + "a".repeat(16 * 1024), null).statusCode());
            assertEquals(400, server.send("/api/login", "POST", "{}", null, "application/json").statusCode());
            assertEquals(401, server.request("/api/login", "POST", "username&password", null).statusCode());
            assertEquals(200, server.request("/api/logout", "POST", "", null).statusCode());
            assertEquals(200, server.request("/api/scrolls?name&unused", "GET", "", null).statusCode());
        }
    }

    @Test void optionalAdminToolsReturnAnExplicitErrorWhenNotConfigured() throws Exception {
        try (var server = new Server(false)) {
            var response = server.request("/api/admin/users", "GET", "", server.login("admin"));
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("Admin tools are unavailable"));
        }
    }

    @Test void multipartHandlesQuotedBoundaryAndIgnoresMisleadingAttributeNames() throws Exception {
        try (var server = new Server()) {
            String cookie = server.login("member");
            String body = "--boundary\r\nContent-Disposition: form-data; xname=\"decoy\"; name=\"name\"\r\n\r\nUploaded\r\n"
                    + "--boundary\r\nContent-Disposition: form-data; name=\"file\"\r\nContent-Type: application/octet-stream\r\n\r\nhello\r\n--boundary--\r\n";
            var response = server.send("/api/scrolls", "POST", body, cookie,
                    "multipart/form-data; charset=UTF-8; boundary=\"boundary\"");
            assertEquals(201, response.statusCode(), response.body());
            assertTrue(response.body().contains("Uploaded"));
            assertTrue(response.body().contains("scroll.bin"));
            var uploaded = server.files.findAll().get(0);
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), server.files.readContent(uploaded.id()));
            assertEquals(400, server.send("/api/scrolls", "POST", "", cookie, "multipart/form-data; boundary=\"\"").statusCode());
            assertEquals(400, server.send("/api/scrolls", "POST", "data", cookie, null).statusCode());
            assertEquals(400, server.send("/api/scrolls", "POST", "--b\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nx\r\n--b--",
                    cookie, "multipart/form-data; boundary=b").statusCode());
        }
    }

    @Test void editingOneSessionDoesNotChangeAnotherUsersIdentity() throws Exception {
        try (var server = new Server()) {
            String member = server.login("member");
            String admin = server.login("admin");
            assertEquals(200, server.request("/api/profile", "POST",
                    "fullName=Changed&email=changed%40example.com&phone=0412345678", member).statusCode());
            var memberSession = server.request("/api/session", "GET", "", member);
            var adminSession = server.request("/api/session", "GET", "", admin);
            assertTrue(memberSession.body().contains("Changed"));
            assertFalse(adminSession.body().contains("Changed"));
            assertTrue(adminSession.body().contains("Library Admin"));
            var logout = server.request("/api/logout", "POST", "", "unrelated=x");
            assertEquals(200, logout.statusCode());
            assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        }
    }

}
