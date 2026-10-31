package vsas;

import org.junit.jupiter.api.Test;
import vsas.ui.WebUi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

class WebUiTest {
    private void withServer(CheckedAction action) throws Exception {
        WebUi ui = new WebUi(0);
        ui.start();
        try { action.run("http://localhost:" + ui.port()); }
        finally { ui.stop(); }
    }

    @FunctionalInterface
    private interface CheckedAction { void run(String base) throws Exception; }

    private HttpResponse<byte[]> request(String base, String path, String method) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(java.time.Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> formRequest(String base, String path, String method,
                                             String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        if (cookie != null) request.header("Cookie", cookie);
        return HttpClient.newHttpClient().send(request.method(method,
                        HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void servesCorrectContentTypesAndSecurityHeaders() throws Exception {
        withServer(base -> {
            String[][] assets = {{"/", "text/html; charset=utf-8"},
                    {"/css/login.css", "text/css; charset=utf-8"},
                    {"/app.js", "text/javascript; charset=utf-8"},
                    {"/images/v24_4.png", "image/png"},
                    {"/images/pixel-library.svg", "image/svg+xml"},
                    {"/images/floating-books.svg", "image/svg+xml"}, {"/health", "text/plain"}};
            for (String[] asset : assets) {
                var response = request(base, asset[0], "GET");
                assertEquals(200, response.statusCode());
                assertEquals(asset[1], response.headers().firstValue("Content-Type").orElseThrow());
                assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            }
            assertArrayEquals(request(base, "/index.html", "GET").body(), request(base, "/", "GET").body());
            assertEquals("OK", new String(request(base, "/health", "GET").body(), java.nio.charset.StandardCharsets.UTF_8));
        });
    }

    @Test void headReturnsGetHeadersAndContentLengthWithoutABody() throws Exception {
        withServer(base -> {
            for (String path : new String[]{"/", "/health", "/missing.html", "/unknown.txt"}) {
                var get = request(base, path, "GET");
                var head = request(base, path, "HEAD");
                assertEquals(get.statusCode(), head.statusCode(), path);
                assertEquals(get.headers().firstValue("Content-Type"), head.headers().firstValue("Content-Type"));
                assertEquals(Integer.toString(get.body().length), head.headers().firstValue("Content-Length").orElseThrow());
                assertEquals(0, head.body().length);
            }
        });
    }

    @Test void rejectsUnsafePathsAndUnsupportedAssets() throws Exception {
        withServer(base -> {
            for (String path : new String[]{"/../index.html", "/%2e%2e/index.html", "/bad%5cpath.html", "/bad%00path.html"}) {
                var response = request(base, path, "GET");
                assertEquals(400, response.statusCode(), path);
                assertEquals("Invalid path", new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
            for (String path : new String[]{"/missing.html", "/missing.css", "/file.txt", "/unknown"}) {
                assertEquals(404, request(base, path, "GET").statusCode(), path);
            }
        });
    }

    @Test void unsupportedMethodsAdvertiseAllowedMethods() throws Exception {
        withServer(base -> {
            for (String method : new String[]{"POST", "PUT", "DELETE", "OPTIONS"}) {
                var response = request(base, "/health", method);
                assertEquals(405, response.statusCode(), method);
                assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElseThrow());
            }
        });
    }

    @Test void servesPackagedScreensAndAssetsAndRejectsUnknownPaths() throws Exception {
        WebUi ui = new WebUi(0);
        ui.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + ui.port();
            for (String path : new String[]{"/", "/login.html", "/register.html", "/dash.html",
                    "/browse.html", "/details.html", "/upload.html", "/profile.html",
                    "/css/login.css", "/app.js", "/images/v24_4.png", "/health"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode(), path);
                assertTrue(response.body().length > 0, path);
            }
            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/missing.html")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            var post = client.send(HttpRequest.newBuilder(URI.create(base + "/")).POST(
                    HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
        } finally { ui.stop(); }
    }

    @Test void registrationLoginSessionAndLogoutWorkEndToEnd() throws Exception {
        withServer(base -> {
            String registration = "username=alice&password=secret&fullName=Alice+Example"
                    + "&confirmPassword=secret&email=alice%40example.com&phone=0412345678";
            var created = formRequest(base, "/api/register", "POST", registration, null);
            assertEquals(201, created.statusCode());
            assertTrue(created.body().contains("\"username\":\"alice\""));
            assertTrue(created.body().matches(".*\"idKey\":\"USR-[A-Z0-9]{8}\".*"));
            assertFalse(created.body().contains("secret"));
            assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());

            var duplicate = formRequest(base, "/api/register", "POST", registration, null);
            assertEquals(400, duplicate.statusCode());
            assertTrue(duplicate.body().contains("Username is already in use"));
            assertEquals(401, formRequest(base, "/api/login", "POST",
                    "username=alice&password=wrong", null).statusCode());
            assertEquals(401, request(base, "/api/session", "GET").statusCode());

            var loggedIn = formRequest(base, "/api/login", "POST",
                    "username=alice&password=secret", null);
            assertEquals(200, loggedIn.statusCode());
            String cookie = loggedIn.headers().firstValue("Set-Cookie").orElseThrow()
                    .split(";", 2)[0];
            assertTrue(cookie.startsWith("vsas_session="));

            var current = formRequest(base, "/api/session", "GET", "", cookie);
            assertEquals(200, current.statusCode());
            assertTrue(current.body().contains("\"fullName\":\"Alice Example\""));

            var loggedOut = formRequest(base, "/api/logout", "POST", "", cookie);
            assertEquals(200, loggedOut.statusCode());
            assertTrue(loggedOut.headers().firstValue("Set-Cookie").orElseThrow()
                    .contains("Max-Age=0"));
            assertEquals(401, formRequest(base, "/api/session", "GET", "", cookie).statusCode());
        });
    }

    @Test void registrationRequiresMatchingPasswordConfirmationBeforeCreatingAnAccount() throws Exception {
        withServer(base -> {
            String details = "username=alice&password=secret&fullName=Alice+Example"
                    + "&email=alice%40example.com&phone=0412345678";
            var missing = formRequest(base, "/api/register", "POST", details, null);
            assertEquals(400, missing.statusCode());
            assertTrue(missing.body().contains("Confirm password is required"));

            var mismatch = formRequest(base, "/api/register", "POST",
                    details + "&confirmPassword=different", null);
            assertEquals(400, mismatch.statusCode());
            assertTrue(mismatch.body().contains("Passwords do not match"));

            var matching = formRequest(base, "/api/register", "POST",
                    details + "&confirmPassword=secret", null);
            assertEquals(201, matching.statusCode(), matching.body());
            assertFalse(matching.body().contains("confirmPassword"));
            assertFalse(matching.body().contains("secret"));
        });
    }

    @Test void profileAndPasswordUpdatesRequireAuthenticationAndPersist() throws Exception {
        withServer(base -> {
            String profile = "fullName=Alice+Updated&email=updated%40example.com&phone=0499999999";
            String password = "currentPassword=secret&newPassword=new-secret&confirmPassword=new-secret";
            for (String endpoint : new String[]{"/api/profile", "/api/password"}) {
                assertEquals(401, formRequest(base, endpoint, "POST", "", null).statusCode());
                assertEquals(405, request(base, endpoint, "GET").statusCode());
            }
            formRequest(base, "/api/register", "POST", "username=alice&password=secret"
                    + "&confirmPassword=secret&fullName=Alice&email=alice%40example.com"
                    + "&phone=0412345678&idKey=alice-01", null);
            var login = formRequest(base, "/api/login", "POST", "username=alice&password=secret", null);
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertEquals(400, formRequest(base, "/api/profile", "POST",
                    "fullName=Alice&email=invalid&phone=0499999999", cookie).statusCode());
            var updated = formRequest(base, "/api/profile", "POST", profile + "&idKey=ignored&type=ADMIN", cookie);
            assertEquals(200, updated.statusCode());
            assertTrue(updated.body().contains("\"fullName\":\"Alice Updated\""));
            assertTrue(updated.body().contains("\"idKey\":\"USR-00000001\""));
            assertTrue(updated.body().contains("\"type\":\"GENERAL\""));
            assertEquals(updated.body(), formRequest(base, "/api/session", "GET", "", cookie).body());
            assertEquals(401, formRequest(base, "/api/password", "POST",
                    password.replace("currentPassword=secret", "currentPassword=wrong"), cookie).statusCode());
            assertEquals(400, formRequest(base, "/api/password", "POST",
                    password.replace("confirmPassword=new-secret", "confirmPassword=different"), cookie).statusCode());
            assertEquals(200, formRequest(base, "/api/password", "POST", password, cookie).statusCode());
            assertEquals(401, formRequest(base, "/api/login", "POST", "username=alice&password=secret", null).statusCode());
            var newLogin = formRequest(base, "/api/login", "POST", "username=alice&password=new-secret", null);
            assertEquals(200, newLogin.statusCode());
            assertTrue(newLogin.body().contains("\"email\":\"updated@example.com\""));
            assertFalse(newLogin.body().contains("new-secret"));
        });
    }

    @Test void accountApiRejectsWrongMethodsContentTypesAndUnknownRoutes() throws Exception {
        withServer(base -> {
            var wrongMethod = request(base, "/api/register", "GET");
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
            assertEquals(404, request(base, "/api/missing", "GET").statusCode());
            var missingContentType = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/register"))
                            .POST(HttpRequest.BodyPublishers.ofString("username=alice")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, missingContentType.statusCode());
            assertTrue(missingContentType.body().contains("application/x-www-form-urlencoded"));
        });
    }
}
