package vsas.ui;

import java.io.Console;
import java.util.Scanner;

/** Runnable terminal adaptation of the screens in figma/*.html. */
public final class TerminalUi {
    private final Scanner input;
    private boolean running = true;
    private String screen = "Home";

    public TerminalUi() { this(new Scanner(System.in)); }

    public TerminalUi(Scanner input) { this.input = input; }

    public void start() {
        while (running) {
            switch (screen) {
                case "Login" -> showLogin();
                case "Register" -> showRegistration();
                case "Dashboard" -> showDashboard();
                case "Browse" -> showBrowseScrolls();
                case "About" -> showAbout();
                default -> showHome();
            }
        }
        System.out.println("Goodbye from VSAS.");
    }

    private void header(String title) {
        System.out.println("\n==================================================");
        System.out.println("VSAS | Virtual Scroll Access System");
        System.out.println("Guest | Guest User");
        System.out.println("--------------------------------------------------");
        System.out.println(title);
    }

    public void showHome() {
        header("Discover. Share. Explore.");
        System.out.println("A digital library for the Whiskers of Edstemus.");
        System.out.println("1. Login\n2. Create account\n3. Browse Scrolls\n4. Dashboard\n5. About\n0. Exit");
        switch (read("Choose an option: ")) {
            case "1" -> screen = "Login";
            case "2" -> screen = "Register";
            case "3" -> screen = "Browse";
            case "4" -> screen = "Dashboard";
            case "5" -> screen = "About";
            case "0" -> running = false;
            default -> invalid();
        }
    }

    public void showLogin() {
        header("Welcome back, seeker");
        System.out.println("Sign in to continue to the library.");
        System.out.println("1. Login\n2. Create account\n0. Back");
        switch (read("Choose an option: ")) {
            case "1" -> {
                read("Username: ");
                readPassword("Password: ");
                unavailable("Login", "UM-02: implement and connect UserService");
            }
            case "2" -> screen = "Register";
            case "0" -> screen = "Home";
            default -> invalid();
        }
    }

    public void showRegistration() {
        header("Create Account");
        System.out.println("Join the guild of knowledge seekers.");
        System.out.println("1. Register\n2. Login\n0. Back");
        switch (read("Choose an option: ")) {
            case "1" -> {
                read("Username: ");
                readPassword("Password: ");
                read("Full name: ");
                read("Email: ");
                read("Phone number: ");
                read("Unique ID key: ");
                unavailable("Registration", "UM-01/07: implement validation, storage and hashing");
            }
            case "2" -> screen = "Login";
            case "0" -> screen = "Home";
            default -> invalid();
        }
    }

    public void showDashboard() {
        header("Welcome, Guest!");
        System.out.println("Knowledge grows when it is shared.");
        System.out.println("Recent Scrolls: storage is not connected yet.");
        showNavigation();
    }

    public void showNavigation() {
        System.out.println("1. Dashboard\n2. Browse Scrolls\n3. Login\n4. Create account\n0. Home");
        switch (read("Choose an option: ")) {
            case "1" -> screen = "Dashboard";
            case "2" -> screen = "Browse";
            case "3" -> screen = "Login";
            case "4" -> screen = "Register";
            case "0" -> screen = "Home";
            default -> invalid();
        }
    }

    public void showBrowseScrolls() {
        header("Browse Scrolls");
        System.out.println("Explore the library of knowledge.");
        System.out.println("Scroll storage is not connected; no scroll data is available.");
        System.out.println("1. Search / filters\n2. Preview by scroll ID\n3. Login to access member features\n0. Back");
        switch (read("Choose an option: ")) {
            case "1" -> {
                read("Uploader ID (blank for any): ");
                read("Scroll ID (blank for any): ");
                read("Name (blank for any): ");
                read("Upload date (YYYY-MM-DD, blank for any): ");
                unavailable("Search", "SS-03: connect ScrollService and persistence");
            }
            case "2" -> showScrollDetails(read("Scroll ID: "));
            case "3" -> screen = "Login";
            case "0" -> screen = "Home";
            default -> invalid();
        }
    }

    public void showScrollDetails(String scrollId) {
        header("Scroll information");
        System.out.println("Selected scroll ID: " + scrollId);
        unavailable("Details / preview", "SS-04: connect stored scroll metadata and binary preview");
    }

    public void showUploadScroll() { memberOnly("Upload a Digital Scroll"); }
    public void showMyScrolls() { memberOnly("My Scrolls"); }
    public void showEditScroll(String scrollId) { memberOnly("Edit my scroll"); }
    public void showProfile() { memberOnly("My Profile"); }
    public void showUserManagement() { memberOnly("User management (admin only)"); }
    public void showScrollStatistics() { memberOnly("Scroll statistics (admin only)"); }

    private void memberOnly(String title) {
        header(title);
        System.out.println("Please log in to access this feature.");
        screen = "Login";
    }

    private void showAbout() {
        header("About VSAS");
        System.out.println("Discover, share and explore binary digital scrolls.");
        System.out.println("This terminal UI follows the local Figma screen structure.");
        System.out.println("Account and scroll services are not implemented yet.");
        read("Press Enter to return home: ");
        screen = "Home";
    }

    private String read(String prompt) {
        System.out.print(prompt);
        if (!running || !input.hasNextLine()) {
            running = false;
            return "";
        }
        return input.nextLine().trim();
    }

    private void readPassword(String prompt) {
        Console console = System.console();
        if (running && console != null) {
            char[] password = console.readPassword("%s", prompt);
            if (password == null) running = false;
            else java.util.Arrays.fill(password, '\0');
        } else if (running) {
            System.out.println("Password input is visible in this terminal.");
            read(prompt);
        }
    }

    private void unavailable(String feature, String todo) {
        if (running) System.out.println(feature + " is not implemented yet. TODO " + todo + ".");
    }

    private void invalid() {
        if (running) System.out.println("Please choose one of the listed options.");
    }
}
