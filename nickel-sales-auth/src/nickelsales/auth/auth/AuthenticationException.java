package nickelsales.auth.auth;

/**
 * Base class for every error AuthenticationService throws, so the login UI
 * can catch this one type and switch on {@link #getCode()} to decide what
 * message to show.
 */
public class AuthenticationException extends Exception {

    private final String code;

    public AuthenticationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable machine-readable code for the UI: INVALID_CREDENTIALS, INACTIVE_USER, LOCKED_USER. */
    public String getCode() {
        return code;
    }
}
