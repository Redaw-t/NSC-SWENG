package shared;

/**
 * Something that only the Main POS may do (give a new product, user, category or supplier its ID, or
 * change one) was asked for on an Additional POS while the Main POS can't be reached. Nothing was
 * saved. The message is meant for the person at the screen (see {@link UiErrors}).
 */
public class MainPosUnavailableException extends RuntimeException {

    public static final String MESSAGE = "This needs the Main POS, and it can't be reached right now. "
            + "Check that the Main POS is on with Nickel Sales open, then try again. Sales still work.";

    public MainPosUnavailableException() {
        super(MESSAGE);
    }

    public MainPosUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
