package pos.checkout;

/**
 * A payment or checkout step can't be done (not enough cash, missing
 * reference number, ...). The message is ready to show on screen.
 */
public class CheckoutException extends Exception {

    private static final long serialVersionUID = 1L;

    public CheckoutException(String message) {
        super(message);
    }
}
