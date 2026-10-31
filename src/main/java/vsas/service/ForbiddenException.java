package vsas.service;

/** Signed-in user attempted an action reserved for the scroll owner. */
public final class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
