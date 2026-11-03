package auth.account;

/** Thrown by UserAccountService; getMessage() is safe to show to the user. */
public class PasswordChangeException extends Exception {
    public PasswordChangeException(String message) {
        super(message);
    }
}