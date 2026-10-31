package vsas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import vsas.model.Scroll;
import vsas.repository.InMemoryScrollRepository;
import vsas.repository.InMemoryUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.LibraryAdminService;
import vsas.service.LibraryScrollService;
import vsas.service.UserService;
import vsas.ui.WebUi;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps the per-scroll counts used by admin cards consistent with actual downloads. */
class AdminDownloadStatisticsApiTest {
    @Test
    void memberAndAdminDownloadsIncreaseOnlyTheRequestedScrollCount() throws Exception {
        try (var server = new Server()) {
            String admin = server.login("admin");
            String member = server.login("member");
            server.assertCounts(admin, 0, 0);

            server.assertDownload(server.first, member, "first content");
            server.assertCounts(admin, 1, 0);

            server.assertDownload(server.first, admin, "first content");
            server.assertCounts(admin, 2, 0);

            server.assertDownload(server.second, member, "second content");
            server.assertCounts(admin, 2, 1);
        }
    }

    @Test
    void previewsBrowsingAndRejectedDownloadsDoNotIncreaseCounts() throws Exception {
        try (var server = new Server()) {
            String admin = server.login("admin");
            String member = server.login("member");
            server.assertDownload(server.first, member, "first content");

            for (Scroll scroll : new Scroll[]{server.first, server.second}) {
                String path = "/api/scrolls/" + scroll.id();
                for (String cookie : new String[]{null, member, admin}) {
                    assertEquals(200, server.request(path + "/preview", "GET", "", cookie).statusCode());
                    assertEquals(200, server.request(path, "GET", "", cookie).statusCode());
                }
                assertEquals(401, server.request(path + "/download", "GET", "", null).statusCode());
                assertEquals(401, server.request(path + "/download", "GET", "",
                        "vsas_session=invalid-session").statusCode());
                assertEquals(405, server.request(path + "/download", "POST", "", member).statusCode());
            }
            assertEquals(200, server.request("/api/scrolls", "GET", "", null).statusCode());
            assertEquals(404, server.request("/api/scrolls/SCR-MISSING/download", "GET", "", member).statusCode());
            server.assertCounts(admin, 1, 0);
        }
    }

    @Test
    void statisticsEndpointRequiresAnAdminSession() throws Exception {
        try (var server = new Server()) {
            String admin = server.login("admin");
            String member = server.login("member");
            server.assertDownload(server.second, member, "second content");

            assertEquals(401, server.request("/api/admin/statistics", "GET", "", null).statusCode());
            assertEquals(401, server.request("/api/admin/statistics", "GET", "",
                    "vsas_session=invalid-session").statusCode());
            assertEquals(403, server.request("/api/admin/statistics", "GET", "", member).statusCode());
            server.assertCounts(admin, 0, 1);
        }
    }

    private static final class Server implements AutoCloseable {
        private final HttpClient client = HttpClient.newHttpClient();
        private final WebUi ui;
        private final Scroll first;
        private final Scroll second;

        private Server() throws Exception {
            var accounts = new InMemoryUserRepository();
            var files = new InMemoryScrollRepository();
            var hasher = new Pbkdf2PasswordHasher(10_000);
            var users = new UserService(accounts, hasher);
            var scrolls = new LibraryScrollService(files);
            var admin = new LibraryAdminService(accounts, hasher, files, scrolls);
            admin.initialiseAdmin("admin", "secret".toCharArray());
            var member = users.register("member", "member", "secret".toCharArray(),
                    "Member", "member@example.com", "0412345678");
            first = scrolls.upload(member, "First notes", null, "first.txt",
                    "first content".getBytes(StandardCharsets.UTF_8));
            second = scrolls.upload(member, "Second notes", null, "second.txt",
                    "second content".getBytes(StandardCharsets.UTF_8));
            ui = new WebUi(0, users, scrolls, admin);
            ui.start();
        }

        private HttpResponse<String> request(String path, String method, String body, String cookie) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded");
            if (cookie != null) builder.header("Cookie", cookie);
            return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private String login(String username) throws Exception {
            var response = request("/api/login", "POST", "username=" + username + "&password=secret", null);
            assertEquals(200, response.statusCode(), response.body());
            return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        }

        private void assertDownload(Scroll scroll, String cookie, String expectedContent) throws Exception {
            var response = request("/api/scrolls/" + scroll.id() + "/download", "GET", "", cookie);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(expectedContent, response.body());
            assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow()
                    .contains(scroll.originalFileName()));
        }

        private void assertCounts(String admin, long firstDownloads, long secondDownloads) throws Exception {
            var response = request("/api/admin/statistics", "GET", "", admin);
            assertEquals(200, response.statusCode(), response.body());
            var rows = Pattern.compile("\\{\"scrollId\":\"([^\"]+)\",\"uploads\":1,\"downloads\":(\\d+),\"removed\":false}")
                    .matcher(response.body());
            Map<String, Long> counts = new LinkedHashMap<>();
            while (rows.find()) {
                assertNull(counts.put(rows.group(1), Long.parseLong(rows.group(2))), "Duplicate statistics row");
            }
            Map<String, Long> expected = Map.of(first.id(), firstDownloads, second.id(), secondDownloads);
            assertEquals(expected, counts, response.body());

            var listing = request("/api/scrolls", "GET", "", admin);
            assertEquals(200, listing.statusCode(), listing.body());
            var cards = Pattern.compile("\\{\"id\":\"([^\"]+)\"[^{}]*\"downloads\":(\\d+)[^{}]*}")
                    .matcher(listing.body());
            Map<String, Long> cardCounts = new LinkedHashMap<>();
            while (cards.find()) {
                assertNull(cardCounts.put(cards.group(1), Long.parseLong(cards.group(2))), "Duplicate scroll card");
            }
            assertEquals(expected, cardCounts, listing.body());

            for (var entry : expected.entrySet()) {
                var detail = request("/api/scrolls/" + entry.getKey(), "GET", "", admin);
                assertEquals(200, detail.statusCode(), detail.body());
                var count = Pattern.compile("\"downloads\":(\\d+)").matcher(detail.body());
                assertTrue(count.find(), detail.body());
                assertEquals(entry.getValue().longValue(), Long.parseLong(count.group(1)), detail.body());
            }
        }

        @Override
        public void close() {
            ui.stop();
        }
    }
}
