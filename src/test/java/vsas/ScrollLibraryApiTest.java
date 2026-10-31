package vsas;

import org.junit.jupiter.api.Test;
import vsas.repository.InMemoryUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.LibraryScrollService;
import vsas.service.UserService;
import vsas.ui.WebUi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;
import vsas.model.Scroll;
import vsas.repository.InMemoryScrollRepository;
import java.time.LocalDate;

class ScrollLibraryApiTest {
    @Test void memberCanUploadBrowseDownloadAndOnlyTheOwnerCanDelete() throws Exception {
        UserService users = new UserService(new InMemoryUserRepository(), new Pbkdf2PasswordHasher());
        users.register("ailinna", "ailinna", "password1".toCharArray(),
                "Ailinna Zhang", "ailinna@example.com", "0412345678");
        users.register("xiangbin", "xiangbin", "password2".toCharArray(),
                "Xiangbin Chen", "xiangbin@example.com", "0498765432");
        WebUi ui = new WebUi(0, users, new LibraryScrollService(new vsas.repository.InMemoryScrollRepository()));
        ui.start();
        try {
            String base = "http://localhost:" + ui.port();
            assertEquals(401, send(base, "/api/scrolls", "POST", "multipart/form-data; boundary=b",
                    body("b", "Notes", null, "hello\nworld\n"), null).statusCode());

            String owner = cookie(send(base, "/api/login", "POST",
                    "application/x-www-form-urlencoded", "username=ailinna&password=password1", null));
            var uploaded = send(base, "/api/scrolls", "POST", "multipart/form-data; boundary=b",
                    body("b", "Agile Notes", "SCR-NOTES", "hello\nworld\n"), owner);
            assertEquals(201, uploaded.statusCode());
            assertFalse(uploaded.body().contains("SCR-NOTES"));
            String id = uploaded.body().replaceAll("(?s).*\"id\":\"(SCR-[A-Z0-9]{8})\".*", "$1");
            assertTrue(id.matches("SCR-[A-Z0-9]{8}"), uploaded.body());
            assertTrue(uploaded.body().contains("\"owned\":true"));
            assertTrue(uploaded.body().contains("hello\\nworld"));

            var listed = send(base, "/api/scrolls?name=agile", "GET", null, "", null);
            assertEquals(200, listed.statusCode());
            assertTrue(listed.body().contains("Agile Notes"));
            assertTrue(listed.body().contains("\"uploaderUsername\":\"ailinna\""));
            assertTrue(listed.body().contains("\"owned\":false"));
            assertEquals(200, send(base, "/api/scrolls/" + id, "GET", null, "", null).statusCode());
            assertEquals("hello\nworld", send(base, "/api/scrolls/" + id + "/preview",
                    "GET", null, "", null).body());
            assertEquals(401, send(base, "/api/scrolls?mine=1", "GET", null, "", null).statusCode());
            assertEquals(401, send(base, "/api/scrolls/" + id, "PUT",
                    "multipart/form-data; boundary=guest",
                    body("guest", "Guest edit", null, "guest content"), null).statusCode());
            assertEquals(401, send(base, "/api/scrolls/" + id, "DELETE", null, "", null).statusCode());

            var download = send(base, "/api/scrolls/" + id + "/download", "GET", null, "", owner);
            assertEquals(200, download.statusCode());
            assertEquals("hello\nworld\n", download.body());
            assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("notes.txt"));
            assertEquals(401, send(base, "/api/scrolls/" + id + "/download", "GET", null, "", null).statusCode());
            assertTrue(send(base, "/api/scrolls/" + id, "GET", null, "", null)
                    .body().contains("\"downloads\":1"));

            String stranger = cookie(send(base, "/api/login", "POST",
                    "application/x-www-form-urlencoded", "username=xiangbin&password=password2", null));
            assertEquals("hello\nworld", send(base, "/api/scrolls/" + id + "/preview", "GET", null, "", null).body());

            var deniedEdit = send(
                    base,
                    "/api/scrolls/" + id,
                    "PUT",
                    "multipart/form-data; boundary=edit",
                    body("edit", "Stolen Notes", null, "changed by stranger"),
                    stranger
            );
            assertEquals(403, deniedEdit.statusCode());

            var edited = send(
                    base,
                    "/api/scrolls/" + id,
                    "PUT",
                    "multipart/form-data; boundary=edit",
                    body("edit", "Updated Agile Notes", null, "updated\ncontent\n"),
                    owner
            );
            assertEquals(200, edited.statusCode());
            assertTrue(edited.body().contains("Updated Agile Notes"));

            var updatedPreview = send(
                    base,
                    "/api/scrolls/" + id + "/preview",
                    "GET",
                    null,
                    "",
                    null
            );
            assertEquals(200, updatedPreview.statusCode());
            assertEquals("updated\ncontent", updatedPreview.body());

            assertEquals(403, send(base, "/api/scrolls/" + id, "DELETE", null, "", stranger).statusCode());
            assertEquals(200, send(base, "/api/scrolls/" + id, "DELETE", null, "", owner).statusCode());
            assertEquals("{\"scrolls\":[]}", send(base, "/api/scrolls", "GET", null, "", null).body());
            assertEquals(404, send(base, "/api/scrolls/" + id + "/preview", "GET", null, "", null).statusCode());
        } finally {
            ui.stop();
        }
    }

    @Test
    void apiSupportsAllFiltersAndEmptyResults() throws Exception {
        var repository = new InMemoryScrollRepository();
        byte[] content = new byte[]{65};

        repository.save(
                new Scroll("SCR-FIRST", "Agile Notes", "evan",
                        LocalDate.of(2026, 10, 20), "first.txt"),
                content
        );

        repository.save(
                new Scroll("SCR-SECOND", "Java Guide", "alex",
                        LocalDate.of(2026, 10, 21), "second.txt"),
                content
        );

        UserService users = new UserService(
                new InMemoryUserRepository(),
                new Pbkdf2PasswordHasher()
        );

        WebUi ui = new WebUi(
                0, users, new LibraryScrollService(repository)
        );

        ui.start();
        try {
            String base = "http://localhost:" + ui.port();

            assertSearchResults(base, "uploaderId=evan", "SCR-FIRST");
            assertSearchResults(base, "scrollId=SCR-FIRST", "SCR-FIRST");
            assertSearchResults(base, "name=AGILE", "SCR-FIRST");
            assertSearchResults(base, "uploadDate=2026-10-21", "SCR-SECOND");

            assertSearchResults(
                    base,
                    "uploaderId=evan&scrollId=SCR-FIRST"
                            + "&name=AGILE&uploadDate=2026-10-20",
                    "SCR-FIRST"
            );

            assertSearchResults(base, "uploaderId=alex&name=AGILE");
            assertSearchResults(base, "name=missing");

            assertEquals(400, send(
                    base, "/api/scrolls?uploadDate=invalid",
                    "GET", null, "", null
            ).statusCode());
        } finally {
            ui.stop();
        }
    }

    private static void assertSearchResults(
            String base, String query, String... expectedIds
    ) throws Exception {
        var response = send(
                base, "/api/scrolls?" + query,
                "GET", null, "", null
        );

        assertEquals(200, response.statusCode(), response.body());

        var actualIds = java.util.regex.Pattern
                .compile("\"id\":\"([^\"]+)\"")
                .matcher(response.body())
                .results()
                .map(match -> match.group(1))
                .toList();

        assertEquals(java.util.List.of(expectedIds), actualIds);
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

    private static String cookie(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static String body(String boundary, String name, String scrollId, String file) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n" + name + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"scrollId\"\r\n\r\n"
                + (scrollId == null ? "" : scrollId) + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + file + "\r\n"
                + "--" + boundary + "--\r\n";
    }
}
