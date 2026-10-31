package vsas.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import vsas.model.Scroll;
import vsas.model.ScrollFilter;
import vsas.model.ScrollStatistics;
import vsas.model.User;
import vsas.model.UserType;
import vsas.repository.InMemoryScrollRepository;
import vsas.repository.InMemoryUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.ActivityLog;
import vsas.service.ForbiddenException;
import vsas.service.Favourites;
import vsas.service.LibraryAdminService;
import vsas.service.LibraryScrollService;
import vsas.service.UserService;

/** Serves the packaged HTML UI and the registration/login API. */
public final class WebUi {
    private static final int MAX_FORM_BYTES = 16 * 1024;
    private static final int MAX_UPLOAD_BYTES = LibraryScrollService.MAX_BYTES + 64 * 1024;
    private static final String SESSION_COOKIE = "vsas_session";
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "png", "image/png",
            "svg", "image/svg+xml");
    private final HttpServer server;
    private final UserService users;
    private final LibraryScrollService scrolls;
    private final LibraryAdminService admin;
    private final ActivityLog activity;
    private final Favourites favourites;
    private final boolean secureCookies;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, User> sessions = new ConcurrentHashMap<>();

    /** Convenience constructor retained for embedded UI tests. */
    public WebUi(int port) throws IOException {
        this(port, new UserService(new InMemoryUserRepository(), new Pbkdf2PasswordHasher()));
    }

    public WebUi(int port, UserService users) throws IOException {
        this(port, users, new LibraryScrollService(new InMemoryScrollRepository()));
    }

    public WebUi(int port, UserService users, LibraryScrollService scrolls) throws IOException {
        this(port, users, scrolls, null);
    }

    public WebUi(int port, UserService users, LibraryScrollService scrolls, LibraryAdminService admin) throws IOException {
        this(port, users, scrolls, admin, new ActivityLog());
    }

    public WebUi(int port, UserService users, LibraryScrollService scrolls,
                 LibraryAdminService admin, ActivityLog activity) throws IOException {
        this(port, users, scrolls, admin, activity, new Favourites());
    }

    public WebUi(int port, UserService users, LibraryScrollService scrolls,
                 LibraryAdminService admin, ActivityLog activity, Favourites favourites) throws IOException {
        this(port, users, scrolls, admin, activity, favourites, false);
    }

    public WebUi(int port, UserService users, LibraryScrollService scrolls,
                 LibraryAdminService admin, ActivityLog activity, Favourites favourites, boolean secureCookies) throws IOException {
        this.secureCookies = secureCookies;
        this.users = Objects.requireNonNull(users, "User service is required");
        this.scrolls = Objects.requireNonNull(scrolls, "Scroll service is required");
        this.admin = admin;
        this.activity = Objects.requireNonNull(activity, "Activity log is required");
        this.favourites = Objects.requireNonNull(favourites, "Favourites store is required");
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::serve);
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }
    public int port() { return server.getAddress().getPort(); }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                serveApi(exchange, path);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                respond(exchange, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/health")) {
                respond(exchange, 200, "text/plain", "OK".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/")) path = "/index.html";
            if (path.equals("/activity.html")) {
                User current = currentUser(exchange);
                if (current == null) {
                    respond(exchange, 401, "text/plain", "Sign in as an administrator".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                try {
                    if (admin == null) throw new ForbiddenException("Only an administrator can do that");
                    admin.requireAdmin(current);
                } catch (ForbiddenException denied) {
                    respond(exchange, 403, "text/plain", denied.getMessage().getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0) {
                respond(exchange, 400, "text/plain", "Invalid path".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String extension = path.substring(path.lastIndexOf('.') + 1);
            if (!TYPES.containsKey(extension)) {
                respond(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (var asset = WebUi.class.getResourceAsStream("/web" + path)) {
                if (asset == null) respond(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                else respond(exchange, 200, TYPES.get(extension), asset.readAllBytes());
            }
        } finally {
            exchange.close();
        }
    }

    private void serveApi(HttpExchange exchange, String path) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        try {
            if (path.equals("/api/favourites")) {
                if (requireMethod(exchange, "GET")) listScrolls(exchange, true);
                return;
            }
            if (path.startsWith("/api/favourites/")) {
                favouriteItem(exchange, path.substring("/api/favourites/".length()));
                return;
            }
            if (path.equals("/api/scrolls")) {
                scrolls(exchange);
                return;
            }
            if (path.startsWith("/api/scrolls/")) {
                scrollItem(exchange, path.substring("/api/scrolls/".length()));
                return;
            }
            if (path.equals("/api/admin/users")) {
                adminUsers(exchange);
                return;
            }
            if (path.startsWith("/api/admin/users/")) {
                deleteAdminUser(exchange, path.substring("/api/admin/users/".length()));
                return;
            }
            if (path.equals("/api/admin/statistics")) {
                adminStatistics(exchange);
                return;
            }
            if (path.equals("/api/admin/activity")) {
                adminActivity(exchange);
                return;
            }
            if (path.startsWith("/api/admin/scrolls/")) {
                deleteAdminScroll(exchange, path.substring("/api/admin/scrolls/".length()));
                return;
            }
            switch (path) {
                case "/api/register" -> register(exchange);
                case "/api/login" -> login(exchange);
                case "/api/logout" -> logout(exchange);
                case "/api/session" -> session(exchange);
                case "/api/profile" -> updateProfile(exchange);
                case "/api/profile/picture" -> updatePicture(exchange);
                case "/api/password" -> changePassword(exchange);
                case "/api/admin/password-recovery" -> issueRecovery(exchange);
                case "/api/password/reset" -> resetPassword(exchange);
                default -> respondJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (ForbiddenException error) {
            respondJson(exchange, 403, errorJson(error.getMessage()));
        } catch (IllegalArgumentException error) {
            respondJson(exchange, 400, errorJson(error.getMessage()));
        } catch (SecurityException error) {
            respondJson(exchange, 401, errorJson(error.getMessage()));
        } catch (NoSuchElementException error) {
            respondJson(exchange, 404, errorJson(error.getMessage()));
        }
    }

    private LibraryAdminService admin() {
        if (admin == null) throw new IllegalArgumentException("Admin tools are unavailable");
        return admin;
    }

    private void adminUsers(HttpExchange exchange) throws IOException {
        User current = currentUser(exchange);
        if (current == null) throw new SecurityException("Sign in as an administrator");
        if (exchange.getRequestMethod().equals("GET")) {
            StringBuilder json = new StringBuilder("{\"users\":[");
            List<User> found = admin().listUsers(current);
            for (int i = 0; i < found.size(); i++) {
                if (i > 0) json.append(',');
                json.append(adminUserJson(found.get(i)));
            }
            json.append("]}");
            respondJson(exchange, 200, json.toString());
            return;
        }
        if (exchange.getRequestMethod().equals("POST")) {
            Map<String, String> form = readForm(exchange);
            char[] password = form.getOrDefault("password", "").toCharArray();
            try {
                UserType type = UserType.valueOf(form.getOrDefault("type", "GENERAL"));
                User created = admin().addUser(current, form.get("idKey"), form.get("username"), password,
                        form.get("fullName"), form.get("email"), form.get("phone"), type);
                respondJson(exchange, 201, adminUserJson(created));
            } catch (IllegalArgumentException error) {
                if ("No enum constant".equals(error.getMessage()) || error.getMessage() != null
                        && error.getMessage().startsWith("No enum constant")) {
                    throw new IllegalArgumentException("Choose a general or admin account");
                }
                throw error;
            } finally {
                Arrays.fill(password, '\0');
            }
            return;
        }
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        respondJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
    }

    private void deleteAdminUser(HttpExchange exchange, String encodedId) throws IOException {
        if (!exchange.getRequestMethod().equals("DELETE")) {
            exchange.getResponseHeaders().set("Allow", "DELETE");
            respondJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        User current = currentUser(exchange);
        if (current == null) throw new SecurityException("Sign in as an administrator");
        String id = URLDecoder.decode(encodedId, StandardCharsets.UTF_8);
        User target = admin().listUsers(current).stream().filter(user -> user.idKey().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("User was not found"));
        if (target.idKey().equals(current.idKey())) {
            throw new IllegalArgumentException("You cannot delete your own account");
        }
        // Clear durable favourites before deletion so a recreated account cannot inherit them.
        try {
            favourites.removeAccount(target);
        } catch (IOException error) {
            respondJson(exchange, 503, errorJson("Favourites storage is unavailable. Account was not deleted."));
            return;
        }
        admin().deleteUser(current, id);
        respondJson(exchange, 200, "{\"message\":\"User deleted\"}");
    }

    private void adminStatistics(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            respondJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        User current = currentUser(exchange);
        if (current == null) throw new SecurityException("Sign in as an administrator");
        StringBuilder json = new StringBuilder("{\"statistics\":[");
        List<ScrollStatistics> rows = admin().statistics(current);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            ScrollStatistics row = rows.get(i);
            json.append("{\"scrollId\":\"").append(json(row.scrollId()))
                    .append("\",\"uploads\":").append(row.uploads())
                    .append(",\"downloads\":").append(row.downloads())
                    .append(",\"removed\":").append(row.removed()).append('}');
        }
        json.append("]}");
        respondJson(exchange, 200, json.toString());
    }

    private void adminActivity(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        User current = currentUser(exchange);
        if (current == null) throw new SecurityException("Sign in as an administrator");
        if (admin == null) throw new ForbiddenException("Only an administrator can do that");
        admin.requireAdmin(current);
        StringBuilder json = new StringBuilder("{\"events\":[");
        List<ActivityLog.Event> events = activity.list();
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) json.append(',');
            ActivityLog.Event event = events.get(i);
            json.append("{\"timestamp\":\"").append(event.timestamp())
                    .append("\",\"actorId\":\"").append(json(event.actorId()))
                    .append("\",\"actorUsername\":\"").append(json(event.actorUsername()))
                    .append("\",\"action\":\"").append(event.action())
                    .append("\",\"targetId\":\"").append(json(event.targetId())).append("\"}");
        }
        json.append(']');
        if (activity.persistencePending()) {
            json.append(",\"warning\":\"Recent activity is not yet saved to disk. Restore activity-log storage.\"");
        }
        json.append('}');
        respondJson(exchange, 200, json.toString());
    }

    private boolean ensureActivityAvailable(HttpExchange exchange) throws IOException {
        try {
            activity.ensureAvailable();
            return true;
        } catch (IOException error) {
            respondJson(exchange, 503, errorJson("Activity log is unavailable. Action was not completed."));
            return false;
        }
    }

    private boolean recordActivity(HttpExchange exchange, User actor,
                                   ActivityLog.Action action, String targetId) throws IOException {
        try {
            activity.record(actor, action, targetId);
            return true;
        } catch (IOException error) {
            respondJson(exchange, 503, errorJson("Action completed, but its activity entry is not yet saved. Restore activity-log storage."));
            return false;
        }
    }

    private void deleteAdminScroll(HttpExchange exchange, String encodedId) throws IOException {
        if (!exchange.getRequestMethod().equals("DELETE")) {
            exchange.getResponseHeaders().set("Allow", "DELETE");
            respondJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        User current = currentUser(exchange);
        if (current == null) throw new SecurityException("Sign in as an administrator");
        String id = URLDecoder.decode(encodedId, StandardCharsets.UTF_8);
        if (!ensureActivityAvailable(exchange)) return;
        admin().deleteScroll(current, id);
        if (!recordActivity(exchange, current, ActivityLog.Action.SCROLL_DELETED, id)) return;
        respondJson(exchange, 200, "{\"message\":\"Scroll deleted\"}");
    }

    private static String adminUserJson(User user) {
        return "{\"idKey\":\"" + json(user.idKey()) + "\",\"username\":\"" + json(user.username())
                + "\",\"fullName\":\"" + json(user.fullName()) + "\",\"email\":\"" + json(user.email())
                + "\",\"phone\":\"" + json(user.phone()) + "\",\"picture\":\"" + json(user.picture())
                + "\",\"type\":\"" + user.type().name() + "\"}";
    }

    private void scrolls(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET")) {
            listScrolls(exchange);
        } else if (method.equals("POST")) {
            uploadScroll(exchange);
        } else {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            respondJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    private void listScrolls(HttpExchange exchange) throws IOException {
        listScrolls(exchange, false);
    }

    private void listScrolls(HttpExchange exchange, boolean favouritesOnly) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = query == null ? Map.of() : readQuery(query);
        User current = currentUser(exchange);
        if (favouritesOnly) current = users.requireCurrentMember(current);
        if (!matchesExpectedAccount(exchange, current)) return;
        String uploaderId = params.get("uploaderId");
        if ("1".equals(params.get("mine"))) {
            if (current == null) throw new SecurityException("Sign in to view your scrolls");
            uploaderId = current.idKey();
        }
        LocalDate uploadDate = null;
        if (params.get("uploadDate") != null && !params.get("uploadDate").isBlank()) {
            try {
                uploadDate = LocalDate.parse(params.get("uploadDate"));
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException("Upload date must be yyyy-MM-dd");
            }
        }
        List<Scroll> found = scrolls.search(new ScrollFilter(
                uploaderId, params.get("scrollId"), params.get("name"), uploadDate));
        if (favouritesOnly) {
            var saved = favourites.list(current);
            // Deleted scrolls never produce cards or download links in the saved view.
            found = found.stream().filter(scroll -> saved.contains(scroll.id())).toList();
        }
        StringBuilder json = new StringBuilder("{\"scrolls\":[");
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) json.append(',');
            json.append(scrollJson(found.get(i), current));
        }
        json.append("]}");
        respondJson(exchange, 200, json.toString());
    }

    private void favouriteItem(HttpExchange exchange, String encodedId) throws IOException {
        User current = users.requireCurrentMember(currentUser(exchange));
        if (!matchesExpectedAccount(exchange, current)) return;
        String id = URLDecoder.decode(encodedId, StandardCharsets.UTF_8);
        if (id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("Scroll ID is invalid");
        }
        String method = exchange.getRequestMethod();
        if (!method.equals("PUT") && !method.equals("DELETE")) {
            exchange.getResponseHeaders().set("Allow", "PUT, DELETE");
            respondJson(exchange, 405, errorJson("Method not allowed"));
            return;
        }
        boolean saved = method.equals("PUT");
        if (saved && scrolls.search(new ScrollFilter(null, id, null, null)).isEmpty()) {
            throw new NoSuchElementException("Scroll was deleted or is no longer available");
        }
        try {
            favourites.set(current, id, saved);
        } catch (IOException error) {
            respondJson(exchange, 503, errorJson("Could not save favourites. Please try again."));
            return;
        }
        respondJson(exchange, 200, "{\"favourite\":" + saved + "}");
    }

    private boolean matchesExpectedAccount(HttpExchange exchange, User current) throws IOException {
        String expectedId = exchange.getRequestHeaders().getFirst("X-VSAS-User-ID");
        if (expectedId != null) expectedId = URLDecoder.decode(expectedId, StandardCharsets.UTF_8);
        String expectedUsername = exchange.getRequestHeaders().getFirst("X-VSAS-Username");
        if (expectedUsername != null) expectedUsername = URLDecoder.decode(expectedUsername, StandardCharsets.UTF_8);
        if ((expectedId != null && (current == null || !expectedId.equals(current.idKey())))
                || (expectedUsername != null && (current == null || !expectedUsername.equals(current.username())))) {
            respondJson(exchange, 409, errorJson("Your account changed. Please refresh and try again."));
            return false;
        }
        return true;
    }

    private void uploadScroll(HttpExchange exchange) throws IOException {
        User current = currentUser(exchange);
        if (current == null) throw new SecurityException("Sign in to upload a scroll");
        Map<String, Part> parts = readMultipart(exchange);
        Part name = parts.get("name");
        Part file = parts.get("file");
        if (name == null || file == null) throw new IllegalArgumentException("Scroll name and file are required");
        if (!ensureActivityAvailable(exchange)) return;
        Scroll created = scrolls.upload(current, partText(name), null, file.fileName(), file.body());
        if (!recordActivity(exchange, current, ActivityLog.Action.SCROLL_UPLOADED, created.id())) return;
        respondJson(exchange, 201, scrollJson(created, current));
    }

    private void scrollItem(HttpExchange exchange, String remainder) throws IOException {
        String decoded = URLDecoder.decode(remainder, StandardCharsets.UTF_8);
        String id = decoded;
        String action = "";
        int slash = decoded.indexOf('/');
        if (slash >= 0) {
            id = decoded.substring(0, slash);
            action = decoded.substring(slash + 1);
        }
        if (id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("Scroll ID is invalid");
        }

        if (action.isEmpty() && exchange.getRequestMethod().equals("PUT")) {
            User current = currentUser(exchange);

            if (current == null) {
                throw new SecurityException("Sign in to edit a scroll");
            }

            Map<String, Part> parts = readMultipart(exchange);
            Part namePart = parts.get("name");
            Part filePart = parts.get("file");

            String newName = namePart == null
                    ? null
                    : partText(namePart);

            if ((newName == null || newName.isBlank()) && filePart == null) {
                throw new IllegalArgumentException(
                        "Enter a new name or choose a replacement file"
                );
            }

            Scroll updated = scrolls.update(
                    current,
                    id,
                    newName,
                    filePart == null ? null : filePart.fileName(),
                    filePart == null ? null : filePart.body()
            );

            respondJson(exchange, 200, scrollJson(updated, current));
            return;
        }

        if (action.isEmpty() && exchange.getRequestMethod().equals("DELETE")) {
            User current = currentUser(exchange);
            if (current == null) throw new SecurityException("Sign in to delete a scroll");
            if (!ensureActivityAvailable(exchange)) return;
            scrolls.remove(current, id);
            if (!recordActivity(exchange, current, ActivityLog.Action.SCROLL_DELETED, id)) return;
            respondJson(exchange, 200, "{\"message\":\"Scroll deleted\"}");
            return;
        }
        if (action.equals("download") && exchange.getRequestMethod().equals("GET")) {
            User current = currentUser(exchange);
            if (current == null) throw new SecurityException("Sign in to download a scroll");
            Scroll scroll = scrolls.search(new ScrollFilter(null, id, null, null)).stream().findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Scroll was not found"));
            byte[] body = scrolls.downloadBytes(current, id);
            String filename = downloadName(scroll.originalFileName(), id);
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            respond(exchange, 200, "application/octet-stream", body);
            return;
        }
        if (action.equals("preview") && exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 200, "text/plain; charset=utf-8", scrolls.preview(id));
            return;
        }
        if (action.isEmpty() && exchange.getRequestMethod().equals("GET")) {
            Scroll scroll = scrolls.search(new ScrollFilter(null, id, null, null)).stream().findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Scroll was not found"));
            respondJson(exchange, 200, scrollJson(scroll, currentUser(exchange)));
            return;
        }
        exchange.getResponseHeaders().set("Allow", action.isEmpty() ? "GET, PUT, DELETE" : "GET");
        respondJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
    }

    private User currentUser(HttpExchange exchange) {
        String token = cookie(exchange, SESSION_COOKIE);
        return token == null ? null : sessions.get(token);
    }

    private String scrollJson(Scroll scroll, User current) {
        boolean owned = current != null && current.idKey().equals(scroll.uploaderId());
        boolean saved = false;
        if (current != null) {
            try {
                saved = favourites.contains(users.requireCurrentMember(current), scroll.id());
            } catch (SecurityException unavailable) {
                // An old session must never expose a deleted/replaced account's favourites.
            }
        }
        String preview = new String(scrolls.preview(scroll.id()), StandardCharsets.UTF_8);
        return "{\"id\":\"" + json(scroll.id()) + "\",\"name\":\"" + json(scroll.name())
                + "\",\"uploaderId\":\"" + json(scroll.uploaderId())
                + "\",\"uploaderUsername\":\"" + json(users.uploaderUsername(scroll.uploaderId()))
                + "\",\"uploadDate\":\"" + scroll.uploadDate()
                + "\",\"originalFileName\":\"" + json(scroll.originalFileName())
                + "\",\"downloads\":" + scrolls.downloadCount(scroll.id())
                + ",\"owned\":" + owned
                + ",\"favourite\":" + saved
                + ",\"preview\":\"" + json(preview) + "\"}";
    }

    private static String downloadName(String original, String id) {
        String name = original == null ? "" : Path.of(original).getFileName().toString();
        String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) safe = id + ".bin";
        return safe;
    }

    private static String partText(Part part) {
        return new String(part.body(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> readQuery(String query) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
            fields.put(key, value);
        }
        return fields;
    }

    private static Map<String, Part> readMultipart(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw new IllegalArgumentException("Use multipart form data");
        }
        String boundary = null;
        for (String piece : contentType.split(";")) {
            String item = piece.trim();
            if (item.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                boundary = item.substring("boundary=".length()).replace("\"", "");
            }
        }
        if (boundary == null || boundary.isBlank()) throw new IllegalArgumentException("Upload is missing a boundary");
        byte[] body = exchange.getRequestBody().readNBytes(MAX_UPLOAD_BYTES + 1);
        if (body.length > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Scroll file must be 8 MB or smaller");
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        Map<String, Part> parts = new LinkedHashMap<>();
        int cursor = indexOf(body, marker, 0);
        while (cursor >= 0) {
            int start = cursor + marker.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') break;
            if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') start += 2;
            int next = indexOf(body, marker, start);
            if (next < 0) break;
            int headerEnd = indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}, start);
            if (headerEnd < 0 || headerEnd > next) throw new IllegalArgumentException("Upload form is invalid");
            String headers = new String(body, start, headerEnd - start, StandardCharsets.ISO_8859_1);
            int contentStart = headerEnd + 4;
            int contentEnd = next >= 2 && body[next - 2] == '\r' && body[next - 1] == '\n' ? next - 2 : next;
            if (contentEnd < contentStart) throw new IllegalArgumentException("Upload form is invalid");
            byte[] content = Arrays.copyOfRange(body, contentStart, contentEnd);
            String name = dispositionValue(headers, "name");
            if (name != null) parts.put(name, new Part(dispositionValue(headers, "filename"), content));
            cursor = next;
        }
        return parts;
    }

    private static String dispositionValue(String headers, String key) {
        for (String line : headers.split("\r\n")) {
            if (!line.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            String token = key.toLowerCase(Locale.ROOT) + "=\"";
            int found = -1;
            for (int fromSearch = 0; fromSearch < lower.length(); ) {
                int candidate = lower.indexOf(token, fromSearch);
                if (candidate < 0) break;
                if (candidate == 0 || !Character.isLetterOrDigit(lower.charAt(candidate - 1))) {
                    found = candidate;
                    break;
                }
                fromSearch = candidate + 1;
            }
            if (found < 0) return null;
            int from = found + key.length() + 2;
            int to = line.indexOf('"', from);
            if (to < 0) return null;
            return line.substring(from, to);
        }
        return null;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private record Part(String fileName, byte[] body) { }

    private void issueRecovery(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        User actor = currentUser(exchange);
        if (actor == null) throw new SecurityException("Sign in as an administrator");
        if (actor.type() != UserType.ADMIN) throw new ForbiddenException("Administrator access is required");
        String token = users.issuePasswordRecovery(actor, readForm(exchange).get("username"));
        respondJson(exchange, 200, "{\"token\":\"" + token + "\",\"expiresInSeconds\":900}");
    }

    private void resetPassword(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        Map<String, String> form = readForm(exchange);
        char[] password = form.getOrDefault("password", "").toCharArray();
        char[] confirmation = form.getOrDefault("confirmPassword", "").toCharArray();
        try {
            if (confirmation.length == 0 || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Passwords must match");
            }
            User restored = users.recoverPassword(form.get("token"), password);
            sessions.entrySet().removeIf(entry -> entry.getValue().idKey().equals(restored.idKey()));
            respondJson(exchange, 200, "{\"message\":\"Password updated. Sign in with your new password.\"}");
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private void register(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        Map<String, String> form = readForm(exchange);
        char[] password = form.getOrDefault("password", "").toCharArray();
        char[] confirmation = form.getOrDefault("confirmPassword", "").toCharArray();
        try {
            if (confirmation.length == 0) {
                throw new IllegalArgumentException("Confirm password is required");
            }
            if (!Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Passwords do not match");
            }
            User created = users.register(null, form.get("username"), password,
                    form.get("fullName"), form.get("email"), form.get("phone"));
            respondJson(exchange, 201, userJson(created));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        Map<String, String> form = readForm(exchange);
        char[] password = form.getOrDefault("password", "").toCharArray();
        try {
            User authenticated = users.login(form.get("username"), password);
            String token = newSessionToken();
            sessions.put(token, authenticated);
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token
                    + "; Path=/; HttpOnly; SameSite=Lax" + (secureCookies ? "; Secure" : ""));
            respondJson(exchange, 200, userJson(authenticated));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void logout(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        String token = cookie(exchange, SESSION_COOKIE);
        if (token != null) sessions.remove(token);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE
                + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + (secureCookies ? "; Secure" : ""));
        respondJson(exchange, 200, "{\"message\":\"Logged out\"}");
    }

    private void session(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) return;
        String token = cookie(exchange, SESSION_COOKIE);
        User current = token == null ? null : sessions.get(token);
        if (current == null) throw new SecurityException("Not signed in");
        respondJson(exchange, 200, userJson(current));
    }

    private synchronized void updatePicture(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        User actor = currentUser(exchange);
        if (actor == null) throw new SecurityException("Sign in to change your picture");
        byte[] content = exchange.getRequestBody().readNBytes(vsas.service.ProfilePicture.MAX_BYTES + 1);
        User updated = users.updatePicture(actor, content);
        refreshUserSessions(updated);
        respondJson(exchange, 200, userJson(updated));
    }

    private synchronized void updateProfile(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        User actor = currentUser(exchange);
        if (actor == null) throw new SecurityException("Sign in to update your profile");
        Map<String, String> form = readForm(exchange);
        if (!ensureActivityAvailable(exchange)) return;
        User updated = users.updateProfile(actor, actor.idKey(), form.get("fullName"),
                form.get("email"), form.get("phone"), null);
        refreshUserSessions(updated);
        if (!actor.fullName().equals(updated.fullName())) {
            if (!recordActivity(exchange, actor, ActivityLog.Action.PROFILE_NAME_CHANGED, updated.idKey())) return;
        }
        respondJson(exchange, 200, userJson(updated));
    }

    private synchronized void changePassword(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) return;
        User actor = currentUser(exchange);
        if (actor == null) throw new SecurityException("Sign in to change your password");
        Map<String, String> form = readForm(exchange);
        char[] currentPassword = form.getOrDefault("currentPassword", "").toCharArray();
        char[] newPassword = form.getOrDefault("newPassword", "").toCharArray();
        try {
            User verified = users.login(actor.username(), currentPassword);
            if (!form.getOrDefault("newPassword", "").equals(form.get("confirmPassword"))) {
                throw new IllegalArgumentException("New passwords do not match");
            }
            User updated = users.updateProfile(verified, verified.idKey(), verified.fullName(),
                    verified.email(), verified.phone(), newPassword);
            refreshUserSessions(updated);
            respondJson(exchange, 200, "{\"message\":\"Password changed successfully\"}");
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private void refreshUserSessions(User updated) {
        sessions.replaceAll((token, user) -> user.username().equals(updated.username()) ? updated : user);
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (exchange.getRequestMethod().equals(method)) return true;
        exchange.getResponseHeaders().set("Allow", method);
        respondJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
        return false;
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("Use application/x-www-form-urlencoded");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_FORM_BYTES + 1);
        if (body.length > MAX_FORM_BYTES) throw new IllegalArgumentException("Form is too large");
        Map<String, String> fields = new LinkedHashMap<>();
        String encoded = new String(body, StandardCharsets.UTF_8);
        if (encoded.isEmpty()) return fields;
        for (String pair : encoded.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
            fields.put(key, value);
        }
        return fields;
    }

    private String newSessionToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String cookie(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) return pair[1];
            }
        }
        return null;
    }

    private static String userJson(User user) {
        return "{\"idKey\":\"" + json(user.idKey()) + "\",\"username\":\""
                + json(user.username()) + "\",\"fullName\":\"" + json(user.fullName())
                + "\",\"email\":\"" + json(user.email()) + "\",\"phone\":\""
                + json(user.phone()) + "\",\"picture\":\"" + json(user.picture())
                + "\",\"type\":\"" + user.type().name() + "\"}";
    }

    private static String errorJson(String message) {
        return "{\"error\":\"" + json(message == null ? "Request failed" : message) + "\"}";
    }

    private static String json(String value) {
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> encoded.append("\\\\");
                case '"' -> encoded.append("\\\"");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (character < 0x20) encoded.append(String.format("\\u%04x", (int) character));
                    else encoded.append(character);
                }
            }
        }
        return encoded.toString();
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
