package pos.receipt;

/**
 * Prints receipts (PrinterPort in the class diagram). The app uses
 * {@link JavaFxReceiptPrinter}; a different printer (e.g. one driven
 * directly by ESC/POS commands) can be plugged in through AppContext
 * without touching the POS screen.
 */
public interface PrinterPort {

    /**
     * Prints {@code receipt} on the default printer.
     *
     * @throws ReceiptPrintException with a message for the cashier (no printer, printer refused, ...)
     */
    void printReceipt(Receipt receipt) throws ReceiptPrintException;
}
