package pos.receipt;

/**
 * Prints receipts (PrinterPort in the class diagram). The app uses
 * {@link EscPosReceiptPrinter}, which sends ESC/POS commands straight to a
 * thermal receipt printer. A different printer can be plugged in through
 * AppContext without touching the POS screen.
 */
public interface PrinterPort {

    /**
     * Prints {@code receipt} on the default printer.
     *
     * @throws ReceiptPrintException with a message for the cashier (no printer, printer refused, ...)
     */
    void printReceipt(Receipt receipt) throws ReceiptPrintException;
}
