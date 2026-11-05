package pos.receipt;

import barcode.service.Code128;
import barcode.service.EscPosBarcode;

/**
 * Size of the receipt barcode. Kept in one place so the printer and the screen
 * preview draw the same barcode: same bars, same share of the paper width.
 */
final class ReceiptBarcode {

    /** Printer dots per character on a thermal printer (Font A: 32 chars = 384 dots on 58 mm). */
    static final int DOTS_PER_CHAR = 12;
    /** Most dots any supported paper has (80 mm). */
    static final int MAX_DOTS = 576;
    /** Dots kept free so the barcode never touches the printable edge. */
    private static final int SAFETY_DOTS = 16;
    /** Barcode height: about 10 mm, tall enough for scanners to read easily. */
    static final int HEIGHT_DOTS = 80;
    /** Widest bar used (dots); wider bars on wide paper add nothing. */
    private static final int MAX_MODULE_DOTS = 2;

    private ReceiptBarcode() { }

    /** Printable width in dots of a line {@code width} characters wide. */
    static int paperDots(int width) {
        return Math.min(MAX_DOTS, width * DOTS_PER_CHAR);
    }

    /** Dots per module: 2 for a normal invoice on 58 mm paper, 1 if the number is long. */
    static int moduleDots(String value, int width) {
        return EscPosBarcode.moduleDots(Code128.moduleCount(value), paperDots(width) - SAFETY_DOTS, MAX_MODULE_DOTS);
    }

    /** Width of the whole barcode in printer dots. */
    static int barcodeDots(String value, int width) {
        return Code128.moduleCount(value) * moduleDots(value, width);
    }
}
