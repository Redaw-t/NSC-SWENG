package barcode.service;

/**
 * Prints product labels. The app uses {@link EscPosLabelPrinter}; another printer can be swapped in.
 * {@link #render} and {@link #printLabel} must use the same drawing, so the preview is exactly what
 * prints.
 */
public interface LabelPrinterPort {

    /** The label exactly as it will print (for the preview). */
    LabelBitmap render(LabelContent label);

    /**
     * Prints {@code copies} labels (1-100).
     *
     * @throws LabelPrintException with a message ready to show (no printer, printer refused, ...)
     */
    void printLabel(LabelContent label, int copies) throws LabelPrintException;
}
