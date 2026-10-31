package vsas;

import java.nio.file.Path;
import vsas.repository.FileScrollRepository;
import vsas.repository.FileUserRepository;
import vsas.security.Pbkdf2PasswordHasher;
import vsas.service.ActivityLog;
import vsas.service.Favourites;
import vsas.service.LibraryAdminService;
import vsas.service.LibraryScrollService;
import vsas.service.UserService;
import vsas.ui.WebUi;

public final class Main {
    private Main() { }
    public static void main(String[] args) throws Exception {
        WebUi ui = start(System.getenv());
        Runtime.getRuntime().addShutdownHook(new Thread(ui::stop));
        System.out.println("VSAS HTML UI: http://localhost:" + ui.port());
    }

    /** Starts an instance using explicit configuration so callers can stop it cleanly. */
    static WebUi start(java.util.Map<String, String> environment) throws Exception {
        int port = Integer.parseInt(environment.getOrDefault("VSAS_PORT", environment.getOrDefault("PORT", "8080")));
        boolean production = Boolean.parseBoolean(environment.getOrDefault("VSAS_PRODUCTION", "false"));
        String configuredPassword = environment.get("VSAS_ADMIN_PASSWORD");
        if (production && (configuredPassword == null || configuredPassword.length() < 16
                || configuredPassword.equals("Admin#2412"))) {
            throw new IllegalArgumentException("Set a private VSAS_ADMIN_PASSWORD of at least 16 characters for online hosting");
        }
        Path dataDirectory = Path.of(environment.getOrDefault("VSAS_DATA_DIR", "data"));
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
        FileUserRepository userRepository = new FileUserRepository(dataDirectory.resolve("users.db"));
        FileScrollRepository scrollRepository = new FileScrollRepository(dataDirectory.resolve("scrolls"));
        UserService users = new UserService(userRepository, hasher);
        LibraryScrollService scrolls = new LibraryScrollService(scrollRepository);
        LibraryAdminService admin = new LibraryAdminService(userRepository, hasher, scrollRepository, scrolls);
        String adminUsername = environment.getOrDefault("VSAS_ADMIN_USERNAME", "admin");
        char[] adminPassword = environment.getOrDefault("VSAS_ADMIN_PASSWORD", "Admin#2412").toCharArray();
        admin.initialiseAdmin(adminUsername, adminPassword);
        java.util.Arrays.fill(adminPassword, '\0');
        WebUi ui = new WebUi(port, users, scrolls, admin,
                new ActivityLog(dataDirectory.resolve("activity.log")),
                new Favourites(dataDirectory.resolve("favourites.db")),
                Boolean.parseBoolean(environment.getOrDefault("VSAS_SECURE_COOKIES", "false")));
        ui.start();
        return ui;
    }
}
