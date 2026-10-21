package nickelsales.auth.account;

/** Thrown by UserAdminService; getMessage() is safe to show to the user. */
public class AccountException extends Exception {
    public AccountException(String message) {
        super(message);
    }
}
