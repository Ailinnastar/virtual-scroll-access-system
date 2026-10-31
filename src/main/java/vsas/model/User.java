package vsas.model;

// Profile validation and uniqueness are enforced by UserService.
// passwordHash must contain only an encoded salted hash, never the plaintext password.
public record User(String idKey, String username, String passwordHash,
                   String fullName, String email, String phone, UserType type, String picture) {
    public User(String idKey, String username, String passwordHash, String fullName,
                String email, String phone, UserType type) {
        this(idKey, username, passwordHash, fullName, email, phone, type, "");
    }
    @Override
    public String toString() {
        return "User[idKey=" + idKey
                + ", username=" + username
                + ", type=" + type + "]";
    }
}
