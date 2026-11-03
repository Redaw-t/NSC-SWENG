package barcode.service;

/** A label could not be printed. The message is ready to show the user. */
public class LabelPrintException extends Exception {

    private static final long serialVersionUID = 1L;

    public LabelPrintException(String message) {
        super(message);
    }
}
