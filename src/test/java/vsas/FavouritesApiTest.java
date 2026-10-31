package vsas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vsas.ui.WebUi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FavouritesApiTest {
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String EMPTY = "{\"scrolls\":[]}";
    @TempDir Path data;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void guestsCannotReadOrChangeFavouritesAndSeeUnselectedBrowseStars() throws Exception {
        WebUi ui = start();
        try {
            register(ui, "alice");
            String alice = login(ui, "alice", "alice-secret");
            String id = upload(ui, alice, "Public notes");
            change(ui, id, "PUT", alice, true);

            assertEquals(401, send(ui, "/api/favourites", "GET", null, null, null).statusCode());
            assertEquals(401, send(ui, "/api/favourites/" + id, "PUT", null, null, null).statusCode());
            assertEquals(401, send(ui, "/api/favourites/" + id, "DELETE", null, null, null).statusCode());
            String browse = get(ui, "/api/scrolls", null);
            assertTrue(browse.contains("\"id\":\"" + id + "\""), browse);
            assertTrue(browse.contains("\"favourite\":false"), browse);
            assertFalse(browse.contains("\"favourite\":true"), browse);
            assertTrue(get(ui, "/api/favourites", alice).contains(id));
        } finally {
            ui.stop();
        }
    }

    @Test void addAndRemoveAreIdempotentAndPreserveCompleteScrollMetadata() throws Exception {
        WebUi ui = start();
        try {
            String aliceId = register(ui, "alice");
            String alice = login(ui, "alice", "alice-secret");
            String id = upload(ui, alice, "Agile notes");
            assertEquals(EMPTY, get(ui, "/api/favourites", alice));
            change(ui, id, "PUT", alice, true);
            change(ui, id, "PUT", alice, true);

            String favourites = get(ui, "/api/favourites", alice);
            assertEquals(1, occurrences(favourites, "\"id\":"), favourites);
            for (String expected : new String[] {"\"id\":\"" + id + "\"", "\"name\":\"Agile notes\"",
                    "\"uploaderId\":\"" + aliceId + "\"", "\"uploaderUsername\":\"alice\"",
                    "\"uploadDate\":", "\"originalFileName\":\"notes.txt\"", "\"downloads\":0",
                    "\"owned\":true", "\"preview\":\"Test contents\"", "\"favourite\":true"}) {
                assertTrue(favourites.contains(expected), favourites);
            }
            assertTrue(get(ui, "/api/scrolls", alice).contains("\"favourite\":true"));
            change(ui, id, "DELETE", alice, false);
            change(ui, id, "DELETE", alice, false);
            assertEquals(EMPTY, get(ui, "/api/favourites", alice));
            assertTrue(get(ui, "/api/scrolls", alice).contains("\"favourite\":false"));
        } finally {
            ui.stop();
        }
    }

    @Test void accountIdentityComesOnlyFromSessionDespiteSpoofedQueryAndBody() throws Exception {
        WebUi ui = start();
        try {
            String aliceId = register(ui, "alice");
            String bobId = register(ui, "bob");
            String alice = login(ui, "alice", "alice-secret");
            String bob = login(ui, "bob", "bob-secret");
            String first = upload(ui, alice, "Alice bookmark");
            String second = upload(ui, alice, "Bob bookmark");
            change(ui, first, "PUT", alice, true);

            String spoofAlice = "?userId=" + aliceId + "&accountId=" + aliceId + "&idKey=" + aliceId;
            assertEquals(EMPTY, get(ui, "/api/favourites" + spoofAlice, bob));
            assertFalse(get(ui, "/api/scrolls" + spoofAlice, bob).contains("\"favourite\":true"));
            var added = send(ui, "/api/favourites/" + second + spoofAlice, "PUT",
                    "userId=" + aliceId + "&accountId=" + aliceId + "&idKey=" + aliceId, FORM, bob);
            assertEquals(200, added.statusCode(), added.body());
            assertTrue(get(ui, "/api/favourites", bob).contains(second));
            assertFalse(get(ui, "/api/favourites", alice).contains(second));

            var removed = send(ui, "/api/favourites/" + first + spoofAlice, "DELETE",
                    "userId=" + aliceId + "&accountId=" + aliceId, FORM, bob);
            assertEquals(200, removed.statusCode(), removed.body());
            assertTrue(get(ui, "/api/favourites", alice).contains(first));
            assertTrue(get(ui, "/api/favourites?userId=" + bobId, alice).contains(first));
            assertFalse(get(ui, "/api/favourites?userId=" + bobId, alice).contains(second));

            // A tab rendered for Alice must not change Bob's favourites after another tab logs in as Bob.
            assertEquals(409, send(ui, "/api/scrolls", "GET", null, null, bob, aliceId).statusCode());
            assertEquals(409, send(ui, "/api/favourites", "GET", null, null, bob, aliceId).statusCode());
            assertEquals(409, send(ui, "/api/favourites/" + first, "PUT", null, null, bob, aliceId).statusCode());
            assertEquals(409, send(ui, "/api/favourites/" + second, "DELETE", null, null, bob, aliceId).statusCode());
            assertEquals(200, send(ui, "/api/favourites", "GET", null, null, bob, bobId).statusCode());
            assertEquals(200, send(ui, "/api/favourites/" + second, "PUT", null, null, bob, bobId).statusCode());
            assertFalse(get(ui, "/api/favourites", bob).contains(first));
            assertTrue(get(ui, "/api/favourites", bob).contains(second));
        } finally {
            ui.stop();
        }
    }

    @Test void favouritesSurviveLogoutNewLoginAndApplicationRestart() throws Exception {
        WebUi ui = start();
        String id;
        String oldCookie;
        try {
            register(ui, "alice");
            register(ui, "bob");
            oldCookie = login(ui, "alice", "alice-secret");
            id = upload(ui, oldCookie, "Persistent bookmark");
            change(ui, id, "PUT", oldCookie, true);
            assertEquals(200, send(ui, "/api/logout", "POST", "", FORM, oldCookie).statusCode());
            assertEquals(401, send(ui, "/api/favourites", "GET", null, null, oldCookie).statusCode());
            assertEquals(401, send(ui, "/api/favourites/" + id, "DELETE", null, null, oldCookie).statusCode());
            assertTrue(get(ui, "/api/favourites", login(ui, "alice", "alice-secret")).contains(id));
        } finally {
            ui.stop();
        }

        ui = start();
        try {
            assertEquals(401, send(ui, "/api/favourites", "GET", null, null, oldCookie).statusCode());
            assertTrue(get(ui, "/api/favourites", login(ui, "alice", "alice-secret")).contains(id));
            assertEquals(EMPTY, get(ui, "/api/favourites", login(ui, "bob", "bob-secret")));
            change(ui, id, "DELETE", login(ui, "alice", "alice-secret"), false);
        } finally {
            ui.stop();
        }

        ui = start();
        try {
            assertEquals(EMPTY, get(ui, "/api/favourites", login(ui, "alice", "alice-secret")));
        } finally {
            ui.stop();
        }
    }

    @Test void deletedScrollIsOmittedAndCannotBeDownloadedOrAddedAgain() throws Exception {
        WebUi ui = start();
        try {
            register(ui, "alice");
            register(ui, "bob");
            String alice = login(ui, "alice", "alice-secret");
            String bob = login(ui, "bob", "bob-secret");
            String deleted = upload(ui, alice, "Delete me");
            String retained = upload(ui, alice, "Keep me");
            change(ui, deleted, "PUT", bob, true);
            change(ui, retained, "PUT", bob, true);
            assertEquals(200, send(ui, "/api/scrolls/" + deleted, "DELETE", null, null, alice).statusCode());

            String favourites = get(ui, "/api/favourites", bob);
            assertFalse(favourites.contains(deleted), favourites);
            assertTrue(favourites.contains(retained), favourites);
            assertEquals(404, send(ui, "/api/scrolls/" + deleted + "/download", "GET", null, null, bob).statusCode());
            assertEquals(404, send(ui, "/api/favourites/" + deleted, "PUT", null, null, bob).statusCode());
            change(ui, deleted, "DELETE", bob, false);
            assertTrue(get(ui, "/api/favourites", bob).contains(retained));
        } finally {
            ui.stop();
        }
    }

    @Test void favouritesSupportBrowseFiltersWithoutIncludingUnfavouritedScrolls() throws Exception {
        WebUi ui = start();
        try {
            String aliceId = register(ui, "alice");
            String bobId = register(ui, "bob");
            String alice = login(ui, "alice", "alice-secret");
            String bob = login(ui, "bob", "bob-secret");
            String agile = upload(ui, alice, "Agile guide");
            String scrum = upload(ui, bob, "Scrum guide");
            String excluded = upload(ui, bob, "Unstarred guide");
            change(ui, agile, "PUT", alice, true);
            change(ui, scrum, "PUT", alice, true);

            String byName = get(ui, "/api/favourites?name=agile", alice);
            assertTrue(byName.contains(agile), byName);
            assertFalse(byName.contains(scrum), byName);
            String byId = get(ui, "/api/favourites?scrollId=" + scrum, alice);
            assertTrue(byId.contains(scrum), byId);
            assertFalse(byId.contains(agile), byId);
            String byUploader = get(ui, "/api/favourites?uploaderId=" + bobId, alice);
            assertTrue(byUploader.contains(scrum), byUploader);
            assertFalse(byUploader.contains(agile), byUploader);
            assertFalse(byUploader.contains(excluded), byUploader);
            String date = field(get(ui, "/api/scrolls/" + agile, alice), "uploadDate");
            String combined = get(ui, "/api/favourites?name=guide&scrollId=" + agile
                    + "&uploaderId=" + aliceId + "&uploadDate=" + date, alice);
            assertEquals(1, occurrences(combined, "\"id\":"), combined);
            assertTrue(combined.contains(agile), combined);
            assertEquals(EMPTY, get(ui, "/api/favourites?uploadDate=1900-01-01", alice));
            assertEquals(400, send(ui, "/api/favourites?uploadDate=not-a-date", "GET", null, null, alice).statusCode());
        } finally {
            ui.stop();
        }
    }

    @Test void recreatedAccountCannotInheritFavouritesOrUseDeletedAccountsSession() throws Exception {
        WebUi ui = start();
        String id;
        try {
            String administrator = login(ui, "admin", "admin-secret");
            createAccount(ui, administrator, "reused-id", "alice");
            String original = login(ui, "alice", "alice-secret");
            id = upload(ui, administrator, "Shared scroll");
            change(ui, id, "PUT", original, true);
            assertEquals(200, send(ui, "/api/admin/users/reused-id", "DELETE", null, null, administrator).statusCode());
            assertEquals(401, send(ui, "/api/favourites", "GET", null, null, original).statusCode());
            createAccount(ui, administrator, "reused-id", "alice");
            assertEquals(401, send(ui, "/api/favourites", "GET", null, null, original).statusCode());
            assertEquals(401, send(ui, "/api/favourites/" + id, "PUT", null, null, original).statusCode());
            assertEquals(EMPTY, get(ui, "/api/favourites", login(ui, "alice", "alice-secret")));
        } finally {
            ui.stop();
        }

        ui = start();
        try {
            assertEquals(EMPTY, get(ui, "/api/favourites", login(ui, "alice", "alice-secret")));
        } finally {
            ui.stop();
        }
    }

    @Test void invalidIdsUnknownScrollsAndUnsupportedMethodsHaveExplicitResponses() throws Exception {
        WebUi ui = start();
        try {
            register(ui, "alice");
            String alice = login(ui, "alice", "alice-secret");
            String id = upload(ui, alice, "Valid scroll");
            for (String invalid : new String[] {"", "%20", "%2e%2e", "bad%2Fid", "bad%5Cid"}) {
                assertEquals(400, send(ui, "/api/favourites/" + invalid, "PUT", null, null, alice).statusCode(), invalid);
            }
            assertEquals(404, send(ui, "/api/favourites/SCR-00000000", "PUT", null, null, alice).statusCode());
            change(ui, "SCR-00000000", "DELETE", alice, false);
            assertEquals(405, send(ui, "/api/favourites", "POST", "", FORM, alice).statusCode());
            assertEquals(405, send(ui, "/api/favourites/" + id, "GET", null, null, alice).statusCode());
            assertEquals(405, send(ui, "/api/favourites/" + id, "POST", "", FORM, alice).statusCode());
            assertEquals(EMPTY, get(ui, "/api/favourites", alice));
        } finally {
            ui.stop();
        }
    }

    @Test void expectedUsernameRejectsAReusedIdForADifferentAccount() throws Exception {
        WebUi ui = start();
        try {
            String administrator = login(ui, "admin", "admin-secret");
            createAccount(ui, administrator, "reused-id", "alice");
            String id = upload(ui, administrator, "Shared notes");
            assertEquals(200, send(ui, "/api/admin/users/reused-id", "DELETE", null, null, administrator).statusCode());
            createAccount(ui, administrator, "reused-id", "bob");
            String bob = login(ui, "bob", "bob-secret");
            for (String path : new String[] {"/api/scrolls", "/api/favourites", "/api/favourites/" + id}) {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + path))
                        .header("Cookie", bob).header("X-VSAS-User-ID", "reused-id")
                        .header("X-VSAS-Username", "alice")
                        .method(path.endsWith(id) ? "PUT" : "GET", HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(409, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var valid = HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + "/api/favourites/" + id))
                    .header("Cookie", bob).header("X-VSAS-User-ID", "reused-id")
                    .header("X-VSAS-Username", "%62ob")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(200, client.send(valid, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(get(ui, "/api/favourites", bob).contains(id));
        } finally {
            ui.stop();
        }
    }

    @Test void unicodeExpectedIdentityHeadersSupportNonAsciiAccounts() throws Exception {
        WebUi ui = start();
        try {
            String administrator = login(ui, "admin", "admin-secret");
            String id = upload(ui, administrator, "Unicode account notes");
            var created = send(ui, "/api/admin/users", "POST",
                    "idKey=%E8%B4%A6%E5%8F%B7&username=%E5%B0%8F%E9%99%88&password=test-secret"
                            + "&fullName=Demo&email=demo%40example.com&phone=0412345678&type=GENERAL", FORM, administrator);
            assertEquals(201, created.statusCode(), created.body());
            String member = login(ui, "%E5%B0%8F%E9%99%88", "test-secret");
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + "/api/favourites/" + id))
                    .header("Cookie", member).header("X-VSAS-User-ID", "%E8%B4%A6%E5%8F%B7")
                    .header("X-VSAS-Username", "%E5%B0%8F%E9%99%88")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(get(ui, "/api/favourites", member).contains(id));
        } finally {
            ui.stop();
        }
    }

    private WebUi start() throws Exception {
        return Main.start(Map.of("VSAS_PORT", "0", "VSAS_DATA_DIR", data.toString(),
                "VSAS_ADMIN_USERNAME", "admin", "VSAS_ADMIN_PASSWORD", "admin-secret"));
    }

    private String register(WebUi ui, String username) throws Exception {
        var response = send(ui, "/api/register", "POST", "username=" + username
                + "&password=" + username + "-secret&confirmPassword=" + username + "-secret"
                + "&fullName=Test+Member&email=" + username + "%40example.com&phone=0412345678", FORM, null);
        assertEquals(201, response.statusCode(), response.body());
        return field(response.body(), "idKey");
    }

    private void createAccount(WebUi ui, String administrator, String id, String username) throws Exception {
        var response = send(ui, "/api/admin/users", "POST", "idKey=" + id + "&username=" + username
                + "&password=" + username + "-secret&fullName=Test+Member&email=" + username
                + "%40example.com&phone=0412345678&type=GENERAL", FORM, administrator);
        assertEquals(201, response.statusCode(), response.body());
    }

    private String login(WebUi ui, String username, String password) throws Exception {
        var response = send(ui, "/api/login", "POST", "username=" + username + "&password=" + password, FORM, null);
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private String upload(WebUi ui, String cookie, String name) throws Exception {
        String body = "--b\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + name
                + "\r\n--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"notes.txt\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\nTest contents\r\n--b--\r\n";
        var response = send(ui, "/api/scrolls", "POST", body, "multipart/form-data; boundary=b", cookie);
        assertEquals(201, response.statusCode(), response.body());
        return field(response.body(), "id");
    }

    private void change(WebUi ui, String id, String method, String cookie, boolean selected) throws Exception {
        var response = send(ui, "/api/favourites/" + id, method, null, null, cookie);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"favourite\":" + selected), response.body());
    }

    private String get(WebUi ui, String path, String cookie) throws Exception {
        var response = send(ui, path, "GET", null, null, cookie);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> send(WebUi ui, String path, String method, String body,
                                      String contentType, String cookie) throws Exception {
        return send(ui, path, method, body, contentType, cookie, null);
    }

    private HttpResponse<String> send(WebUi ui, String path, String method, String body,
                                      String contentType, String cookie, String expectedUserId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + path))
                .timeout(Duration.ofSeconds(5));
        if (contentType != null) request.header("Content-Type", contentType);
        if (cookie != null) request.header("Cookie", cookie);
        if (expectedUserId != null) request.header("X-VSAS-User-ID", expectedUserId);
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        var match = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(match.find(), json);
        return match.group(1);
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }
}
