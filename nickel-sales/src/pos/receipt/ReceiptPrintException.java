package pos.receipt;

/** A receipt could not be printed. The message is ready to show the cashier. */
public class ReceiptPrintException extends Exception {

    private static final long serialVersionUID = 1L;

    public ReceiptPrintException(String message) {
        super(message);
    }
}
