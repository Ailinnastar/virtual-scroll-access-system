package vsas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.ui.WebUi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityLogApiTest {
    @TempDir Path data;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void activityPageAndApiRequireCurrentAdministrator() throws Exception {
        WebUi ui = start();
        try {
            assertEquals(401, send(ui, "/activity.html", "GET", null, null, null).statusCode());
            assertEquals(401, send(ui, "/api/admin/activity", "GET", null, null, null).statusCode());
            register(ui);
            String member = login(ui, "member", "member-secret");
            assertEquals(403, send(ui, "/activity.html", "GET", null, null, member).statusCode());
            assertEquals(403, send(ui, "/api/admin/activity", "GET", null, null, member).statusCode());
            String administrator = login(ui, "admin", "admin-secret");
            assertEquals(200, send(ui, "/activity.html", "GET", null, null, administrator).statusCode());
            assertEquals("{\"events\":[]}", send(ui, "/api/admin/activity", "GET", null, null, administrator).body());
            assertEquals(405, send(ui, "/api/admin/activity", "POST", "", null, administrator).statusCode());
        } finally {
            ui.stop();
        }
    }

    @Test void successfulNameUploadAndDeleteEventsSurviveRestartWithoutCredentials() throws Exception {
        WebUi ui = start();
        String firstRead;
        try {
            String memberId = register(ui);
            String member = login(ui, "member", "member-secret");
            String administrator = login(ui, "admin", "admin-secret");
            String profile = "fullName=Member+Name&email=member%40example.com&phone=0412345678";
            assertEquals(200, send(ui, "/api/profile", "POST", profile,
                    "application/x-www-form-urlencoded", member).statusCode());
            firstRead = activity(ui, administrator);
            assertEquals("{\"events\":[]}", firstRead);

            assertEquals(200, send(ui, "/api/profile", "POST",
                    "fullName=Changed+Name&email=member%40example.com&phone=0412345678",
                    "application/x-www-form-urlencoded", member).statusCode());
            String upload = multipart("b", "Useful Scroll", "notes.txt", "file contents");
            var uploaded = send(ui, "/api/scrolls", "POST", upload, "multipart/form-data; boundary=b", member);
            assertEquals(201, uploaded.statusCode());
            String id = uploaded.body().replaceAll("(?s).*\"id\":\"(SCR-[A-Z0-9]{8})\".*", "$1");
            assertTrue(id.matches("SCR-[A-Z0-9]{8}"), uploaded.body());
            assertEquals(400, send(ui, "/api/scrolls", "POST", upload,
                    "multipart/form-data; boundary=b", member).statusCode());
            assertEquals(404, send(ui, "/api/scrolls/missing", "DELETE", null, null, member).statusCode());
            assertEquals(200, send(ui, "/api/scrolls/" + id, "DELETE", null, null, member).statusCode());

            firstRead = activity(ui, administrator);
            assertEquals(3, occurrences(firstRead, "\"action\":"), firstRead);
            assertTrue(firstRead.indexOf("SCROLL_DELETED") < firstRead.indexOf("SCROLL_UPLOADED"), firstRead);
            assertTrue(firstRead.indexOf("SCROLL_UPLOADED") < firstRead.indexOf("PROFILE_NAME_CHANGED"), firstRead);
            assertTrue(firstRead.contains("\"actorId\":\"" + memberId + "\""), firstRead);
            assertTrue(firstRead.contains("\"targetId\":\"" + id + "\""), firstRead);
            assertTrue(firstRead.contains("\"targetId\":\"" + memberId + "\""), firstRead);
            assertTrue(firstRead.contains("\"timestamp\":"), firstRead);
            assertFalse(firstRead.contains("member-secret"), firstRead);
            assertFalse(firstRead.contains("passwordHash"), firstRead);
            assertFalse(firstRead.contains("file contents"), firstRead);
        } finally {
            ui.stop();
        }

        ui = start();
        try {
            assertEquals(firstRead, activity(ui, login(ui, "admin", "admin-secret")));
        } finally {
            ui.stop();
        }
    }

    @Test void oldAdministratorSessionCannotReadLogAfterItsIdIsReused() throws Exception {
        WebUi ui = start();
        try {
            String original = login(ui, "admin", "admin-secret");
            assertEquals(201, send(ui, "/api/admin/users", "POST",
                    "idKey=backup&username=backup&password=backup-secret&fullName=Backup+Admin"
                            + "&email=backup%40example.com&phone=0412345678&type=ADMIN",
                    "application/x-www-form-urlencoded", original).statusCode());
            String backup = login(ui, "backup", "backup-secret");
            assertEquals(200, send(ui, "/api/admin/users/admin", "DELETE", null, null, backup).statusCode());
            assertEquals(201, send(ui, "/api/admin/users", "POST",
                    "idKey=admin&username=replacement&password=new-secret&fullName=New+Admin"
                            + "&email=new%40example.com&phone=0412345678&type=ADMIN",
                    "application/x-www-form-urlencoded", backup).statusCode());
            assertEquals(403, send(ui, "/api/admin/activity", "GET", null, null, original).statusCode());
            assertEquals(403, send(ui, "/activity.html", "GET", null, null, original).statusCode());
        } finally {
            ui.stop();
        }
    }

    @Test void administratorDeletionRecordsAdministratorAsActor() throws Exception {
        WebUi ui = start();
        try {
            register(ui);
            String member = login(ui, "member", "member-secret");
            String administrator = login(ui, "admin", "admin-secret");
            var uploaded = send(ui, "/api/scrolls", "POST",
                    multipart("b", "Admin delete example", "notes.txt", "content"),
                    "multipart/form-data; boundary=b", member);
            assertEquals(201, uploaded.statusCode());
            String id = uploaded.body().replaceAll("(?s).*\"id\":\"(SCR-[A-Z0-9]{8})\".*", "$1");
            assertEquals(200, send(ui, "/api/admin/scrolls/" + id, "DELETE", null, null,
                    administrator).statusCode());
            String events = activity(ui, administrator);
            assertTrue(events.contains("\"action\":\"SCROLL_DELETED\",\"targetId\":\"" + id + "\""), events);
            assertTrue(events.contains("\"actorId\":\"admin\",\"actorUsername\":\"admin\""), events);
        } finally {
            ui.stop();
        }
    }

    @Test void unavailableActivityStorageBlocksUploadBeforeItIsSaved() throws Exception {
        WebUi ui = start();
        try {
            register(ui);
            String member = login(ui, "member", "member-secret");
            Files.createDirectory(data.resolve("activity.log"));
            var response = send(ui, "/api/scrolls", "POST",
                    multipart("b", "Blocked upload", "notes.txt", "content"),
                    "multipart/form-data; boundary=b", member);
            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("Activity log is unavailable"), response.body());
            assertEquals("{\"scrolls\":[]}", send(ui, "/api/scrolls", "GET", null, null, member).body());
        } finally {
            ui.stop();
        }
    }

    private WebUi start() throws Exception {
        return Main.start(Map.of("VSAS_PORT", "0", "VSAS_DATA_DIR", data.toString(),
                "VSAS_ADMIN_USERNAME", "admin", "VSAS_ADMIN_PASSWORD", "admin-secret"));
    }

    private String register(WebUi ui) throws Exception {
        var response = send(ui, "/api/register", "POST",
                "username=member&password=member-secret&confirmPassword=member-secret"
                        + "&fullName=Member+Name&email=member%40example.com&phone=0412345678",
                "application/x-www-form-urlencoded", null);
        assertEquals(201, response.statusCode());
        String id = response.body().replaceAll("(?s).*\"idKey\":\"([^\"]+)\".*", "$1");
        assertTrue(id.startsWith("USR-"), response.body());
        return id;
    }

    private String login(WebUi ui, String username, String password) throws Exception {
        var response = send(ui, "/api/login", "POST", "username=" + username + "&password=" + password,
                "application/x-www-form-urlencoded", null);
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private String activity(WebUi ui, String cookie) throws Exception {
        var response = send(ui, "/api/admin/activity", "GET", null, null, cookie);
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private HttpResponse<String> send(WebUi ui, String path, String method, String body,
                                      String contentType, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + path))
                .timeout(Duration.ofSeconds(5));
        if (contentType != null) request.header("Content-Type", contentType);
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String multipart(String boundary, String name, String filename, String content) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + name
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n" + content
                + "\r\n--" + boundary + "--\r\n";
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }
}
