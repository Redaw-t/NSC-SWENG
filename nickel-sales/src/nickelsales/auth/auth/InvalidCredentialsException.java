package nickelsales.auth.auth;

/**
 * Wrong user ID or wrong password. Deliberately ONE message/code for both
 * cases, so the login screen never reveals which of the two was wrong (and
 * never reveals which user IDs exist).
 */
public class InvalidCredentialsException extends AuthenticationException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", "Invalid user ID or password.");
    }
}
