package product.contract;

/** A barcode could not be listed or saved. The message is ready to show the user. */
public class BarcodeAssignException extends Exception {

    private static final long serialVersionUID = 1L;

    public BarcodeAssignException(String message) {
        super(message);
    }
}
