package system.service;

/** A System module action that could not be done; the message is ready to show the user. */
public class SystemException extends Exception {

    public SystemException(String message) {
        super(message);
    }

    public SystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
