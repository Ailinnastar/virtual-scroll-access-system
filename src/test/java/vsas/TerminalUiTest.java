package vsas;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import vsas.ui.TerminalUi;
import static org.junit.jupiter.api.Assertions.*;

class TerminalUiTest {
    private String capture(String input, Consumer<TerminalUi> action) {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Scanner scanner = new Scanner(input);
             PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            action.accept(new TerminalUi(scanner));
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test void homeExitAndEndOfInputStopCleanly() {
        for (String input : new String[]{"0\n", ""}) {
            String output = capture(input, TerminalUi::start);
            assertTrue(output.contains("Discover. Share. Explore."));
            assertTrue(output.contains("Guest | Guest User"));
            assertTrue(output.contains("Goodbye from VSAS."));
            assertFalse(output.contains("Please choose one"));
        }
    }

    @Test void homeRoutesToEachScreenAndBack() {
        String[][] routes = {{"1\n0\n0\n", "Welcome back, seeker"},
                {"2\n0\n0\n", "Create Account"}, {"3\n0\n0\n", "Explore the library"},
                {"4\n0\n0\n", "Welcome, Guest!"}, {"5\n\n0\n", "About VSAS"}};
        for (String[] route : routes) {
            String output = capture(route[0], TerminalUi::start);
            assertTrue(output.contains(route[1]), route[1]);
            assertTrue(output.contains("Goodbye from VSAS."));
        }
    }

    @Test void invalidChoicesShowFeedbackAndAllowExit() {
        for (String input : new String[]{"bad\n0\n", "1\nbad\n0\n0\n",
                "2\nbad\n0\n0\n", "3\nbad\n0\n0\n", "4\nbad\n0\n0\n"}) {
            String output = capture(input, TerminalUi::start);
            assertTrue(output.contains("Please choose one of the listed options."));
            assertTrue(output.contains("Goodbye from VSAS."));
        }
    }

    @Test void loginAndRegistrationCollectDetailsAndExplainUnavailableServices() {
        String login = capture("1\n1\nalice\nsecret\n0\n0\n", TerminalUi::start);
        assertTrue(login.contains("Username: "));
        assertTrue(login.contains("Password: "));
        assertTrue(login.contains("Login is not implemented yet. TODO UM-02"));
        assertFalse(login.contains("secret"));
        String registration = capture("2\n1\nalice\nsecret\nAlice\na@example.com\n0412345678\nid\n0\n0\n", TerminalUi::start);
        for (String prompt : new String[]{"Full name: ", "Email: ", "Phone number: ", "Unique ID key: "}) {
            assertTrue(registration.contains(prompt));
        }
        assertTrue(registration.contains("Registration is not implemented yet. TODO UM-01/07"));
        assertFalse(registration.contains("secret"));
    }

    @Test void loginAndRegistrationLinkToEachOther() {
        String output = capture("1\n2\n2\n0\n0\n", TerminalUi::start);
        assertTrue(output.contains("Create Account"));
        assertEquals(2, output.split("Welcome back, seeker", -1).length - 1);
    }

    @Test void dashboardNavigationRoutesToEachDestination() {
        String[][] routes = {{"1\n0\n0\n", "Welcome, Guest!"},
                {"2\n0\n0\n", "Explore the library"},
                {"3\n0\n0\n", "Welcome back, seeker"}, {"4\n0\n0\n", "Create Account"}};
        for (String[] route : routes) {
            String output = capture(route[0], ui -> { ui.showNavigation(); ui.start(); });
            assertTrue(output.contains(route[1]));
        }
    }

    @Test void browseCollectsFiltersPreviewsSelectedIdAndLinksToLogin() {
        String search = capture("3\n1\nuploader\nid\nname\n2026-10-14\n0\n0\n", TerminalUi::start);
        for (String prompt : new String[]{"Uploader ID", "Scroll ID", "Name (blank", "Upload date"}) {
            assertTrue(search.contains(prompt));
        }
        assertTrue(search.contains("Search is not implemented yet. TODO SS-03"));
        String preview = capture("3\n2\n  scroll-1  \n0\n0\n", TerminalUi::start);
        assertTrue(preview.contains("Selected scroll ID: scroll-1"));
        assertTrue(preview.contains("Details / preview is not implemented yet. TODO SS-04"));
        assertTrue(capture("3\n3\n0\n0\n", TerminalUi::start).contains("Welcome back, seeker"));
    }

    @Test void memberAndAdminScreensRedirectGuestsToLogin() {
        for (Consumer<TerminalUi> screen : java.util.List.<Consumer<TerminalUi>>of(
                TerminalUi::showUploadScroll, TerminalUi::showMyScrolls,
                ui -> ui.showEditScroll("id"), TerminalUi::showProfile,
                TerminalUi::showUserManagement, TerminalUi::showScrollStatistics)) {
            String output = capture("0\n0\n", ui -> { screen.accept(ui); ui.start(); });
            assertTrue(output.contains("Please log in to access this feature."));
            assertTrue(output.contains("Welcome back, seeker"));
        }
    }

    @Test void endOfInputDuringFormsDoesNotReportSuccessOrPendingAction() {
        for (String input : new String[]{"1\n1\nalice\n", "2\n1\n", "3\n1\n", "3\n2\n"}) {
            String output = capture(input, TerminalUi::start);
            assertTrue(output.contains("Goodbye from VSAS."));
            assertFalse(output.contains("is not implemented yet. TODO"));
        }
    }
}
