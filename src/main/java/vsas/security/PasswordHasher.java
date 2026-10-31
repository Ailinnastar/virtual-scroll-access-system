package vsas.security;

/** Boundary for salted password hashing and verification. */
public interface PasswordHasher {
    String hash(char[] password);
    boolean verify(char[] password, String encodedHash);
}
