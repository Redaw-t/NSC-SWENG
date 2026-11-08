package pos.returns;

/** A void can't be done (nothing left to void, quantity too high ...). The message is ready to show. */
public class ReturnException extends Exception {

    private static final long serialVersionUID = 1L;

    public ReturnException(String message) {
        super(message);
    }
}
