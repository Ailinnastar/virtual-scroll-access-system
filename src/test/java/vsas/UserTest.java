package vsas;

import org.junit.jupiter.api.Test;
import vsas.model.User;
import vsas.model.UserType;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UserTest {
    @Test
    void stringRepresentationDoesNotExposePasswordHash() {
        String storedHash = "pbkdf2-sensitive-hash";

        User user = new User(
                "evan-id",
                "evan",
                storedHash,
                "Evan",
                "evan@example.com",
                "0412345678",
                UserType.GENERAL
        );

        assertFalse(user.toString().contains(storedHash));
    }
}
