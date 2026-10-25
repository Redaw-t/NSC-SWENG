package auth.login;

/** Credentials are correct, but the account is Locked. */
public class LockedUserException extends AuthenticationException {

    private final String userId;

    public LockedUserException(String userId) {
        super("LOCKED_USER", "This account is locked. Please contact the administrator.");
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
