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

    /**
     * Prints several receipts one after the other as ONE print job (e.g. a refund's sales invoice and
     * its RETURN slip), so the printer never has a second job left waiting in the queue. The default
     * prints them one by one; {@link EscPosReceiptPrinter} sends them together.
     *
     * @throws ReceiptPrintException with a message for the cashier; nothing is printed
     */
    default void printReceipts(java.util.List<Receipt> receipts) throws ReceiptPrintException {
        for (Receipt r : receipts) {
            printReceipt(r);
        }
    }
}
