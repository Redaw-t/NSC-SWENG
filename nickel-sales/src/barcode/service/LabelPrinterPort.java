package barcode.service;

/** Prints a barcode label. The app uses {@link EscPosLabelPrinter}; another printer can be swapped in. */
public interface LabelPrinterPort {

    /**
     * Prints {@code copies} labels (1-100) with the product name, the barcode bars and its number.
     *
     * @throws LabelPrintException with a message ready to show (no printer, printer refused, ...)
     */
    void printLabel(String productName, String barcode, int copies) throws LabelPrintException;
}
