package vsas;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;

// TODO: replace each disabled placeholder with meaningful behaviour tests.
// These are skipped deliberately and provide no evidence of working features.
@Disabled("Scaffold only: implement services and acceptance tests before enabling")
class FeatureAcceptanceTest {
    @Test void registrationLoginAndProfileUpdates() { fail("TODO UM-01/02/03: validation, duplicate IDs, login, profile/password updates"); }
    @Test void guestPermissionsAndUserDisplay() { fail("TODO UM-04/06: view/preview allowed; upload/download denied; name/type shown"); }
    @Test void adminManagementAndHistoricalStatistics() { fail("TODO UM-05: admin creation, list/add/delete users, counters after scroll removal"); }
    @Test void passwordsAreHashedAndVerified() { fail("TODO UM-07: correct/incorrect passwords, salted hashes, no plaintext persistence"); }
    @Test void scrollUploadEditAndRemoval() { fail("TODO SM-01/02/03: uniqueness, binary round trip, uploader ownership, guest denial"); }
    @Test void browsingDownloadFiltersAndPreview() { fail("TODO SS-01/02/03/04: four filters, guest preview, browse-screen download, counters"); }
    @Test void dataSurvivesRestart() { fail("TODO DATA-01/02: profiles, hashes, metadata, binary files and historical statistics"); }
}
