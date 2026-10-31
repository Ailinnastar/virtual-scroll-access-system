package vsas.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pbkdf2PasswordHasherTest {
    private final Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(10_000);

    @Test void hashesWithUniqueSaltsAndVerifiesTheCorrectPassword() {
        String first = hasher.hash("correct horse".toCharArray());
        String second = hasher.hash("correct horse".toCharArray());
        assertTrue(first.startsWith("pbkdf2-sha256$10000$"));
        assertNotEquals(first, second);
        assertTrue(hasher.verify("correct horse".toCharArray(), first));
        assertFalse(hasher.verify("wrong".toCharArray(), first));
    }

    @Test void rejectsMissingPasswordsAndMalformedHashes() {
        assertThrows(IllegalArgumentException.class, () -> new Pbkdf2PasswordHasher(9_999));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(new char[0]));
        for (String value : new String[]{null, "", "wrong$10000$x$y",
                "pbkdf2-sha256$bad$x$y", "pbkdf2-sha256$9999$x$y",
                "pbkdf2-sha256$10000$not-base64!$also-bad!"}) {
            assertFalse(hasher.verify("password".toCharArray(), value));
        }
        assertFalse(hasher.verify(null, hasher.hash("password".toCharArray())));
    }
}
