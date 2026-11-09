package barcode.service;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import shared.PrinterSettings;

import java.io.ByteArrayOutputStream;

/**
 * Prints labels on a thermal printer with ESC/POS commands (printer: the label printer chosen in
 * Settings > Printers, else NSC_PRINTER_NAME, else the default printer).
 *
 * The whole label (store name, product name, bars, number, prices) is drawn by {@link LabelRenderer}
 * and sent as ONE picture (GS v 0), never with the printer's own fonts or barcode command. So every
 * printer model prints exactly the dots the preview shows.
 */
public class EscPosLabelPrinter implements LabelPrinterPort {

    public static final int MAX_COPIES = 100;
    private static final int FEED_LINES = 3;

    private final java.util.function.Supplier<String> storeName;   // asked for each label
    private final int widthDots;

    /** A 58 mm printer (384 dots wide). */
    public EscPosLabelPrinter(String storeName) {
        this(storeName, LabelRenderer.WIDTH_58MM);
    }

    /** @param widthDots the paper's printable width in dots (384 for 58 mm, 576 for 80 mm) */
    public EscPosLabelPrinter(String storeName, int widthDots) {
        this(() -> storeName, widthDots);
    }

    /** A 58 mm printer; the store name is asked for each label, so a change shows straight away. */
    public EscPosLabelPrinter(java.util.function.Supplier<String> storeName) {
        this(storeName, LabelRenderer.WIDTH_58MM);
    }

    public EscPosLabelPrinter(java.util.function.Supplier<String> storeName, int widthDots) {
        this.storeName = java.util.Objects.requireNonNull(storeName, "storeName");
        this.widthDots = widthDots;
    }

    @Override
    public LabelBitmap render(LabelContent label) {
        return LabelRenderer.render(storeName.get(), label, widthDots);
    }

    @Override
    public void printLabel(LabelContent label, int copies) throws LabelPrintException {
        if (copies < 1 || copies > MAX_COPIES) {
            throw new LabelPrintException("Copies must be 1 to " + MAX_COPIES + ".");
        }
        String problem = BarcodeValidator.problemWith(label.barcode());
        if (problem != null) {
            throw new LabelPrintException(problem);
        }
        PrintService service = findPrinter();
        byte[] data = toBytes(render(label), copies);
        try {
            DocPrintJob job = service.createPrintJob();
            job.print(new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), null);
        } catch (PrintException e) {
            throw new LabelPrintException("The printer \"" + service.getName() + "\" did not accept the label. "
                    + "Check it is on and has paper, then print again.");
        }
    }

    /** The raw bytes for the print job (public so a check can verify them without a printer). */
    public static byte[] toBytes(LabelBitmap label, int copies) {
        byte[] picture = label.toEscPosRaster();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);                                  // ESC @ : reset
        out.write(0x40);
        for (int i = 0; i < copies; i++) {
            out.write(0x1B);                              // ESC a 1 : centre
            out.write(0x61);
            out.write(1);
            out.write(picture, 0, picture.length);
            out.write(0x1B);                              // ESC d n : feed, so labels can be torn apart
            out.write(0x64);
            out.write(FEED_LINES);
        }
        return out.toByteArray();
    }

    /** The printer chosen in Settings > Printers, else NSC_PRINTER_NAME, else the Windows default. */
    private static PrintService findPrinter() throws LabelPrintException {
        try {
            return PrinterSettings.find(PrinterSettings.Use.LABEL);
        } catch (PrinterSettings.NoPrinterException e) {
            throw new LabelPrintException(e.getMessage());
        }
    }
}
