package pos.cart;

/**
 * Something the cashier tried can't be done (unknown barcode, product no
 * longer sold, bad quantity, ...). The message is ready to show on screen.
 * Not enough stock is reported separately, as
 * {@link inventory.contract.InsufficientStockException}.
 */
public class CartException extends Exception {

    private static final long serialVersionUID = 1L;

    public CartException(String message) {
        super(message);
    }
}
