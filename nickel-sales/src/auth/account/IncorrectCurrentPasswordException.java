package auth.account;

/** The "current password" typed when changing a password was wrong. getMessage() is safe to show. */
public class IncorrectCurrentPasswordException extends PasswordChangeException {
    public IncorrectCurrentPasswordException() {
        super("Current password is incorrect.");
    }
}
