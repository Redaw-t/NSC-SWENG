package nickelsales.auth.auth;

/** Credentials are correct, but the account is Inactive. */
public class InactiveUserException extends AuthenticationException {

    private final String userId;

    public InactiveUserException(String userId) {
        super("INACTIVE_USER", "This account is inactive. Please contact the administrator.");
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
