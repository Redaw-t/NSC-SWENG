package barcode.service;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prints labels straight to a thermal printer with ESC/POS commands, the same way the receipt
 * printer does (printer: NSC_PRINTER_NAME, else the default printer). The bars are drawn by the
 * printer itself (GS k), so no image is needed. EAN-13 / EAN-8 / UPC-A use the printer's own
 * barcode types; any other code is printed as CODE128.
 *
 * Text is plain ASCII (anything else prints as '?'). A 58 mm printer fits about 32 characters.
 */
public class EscPosLabelPrinter implements LabelPrinterPort {

    public static final int MAX_COPIES = 100;
    private static final int NAME_WIDTH = 32;
    private static final int FEED_LINES = 3;

    @Override
    public void printLabel(String productName, String barcode, int copies) throws LabelPrintException {
        if (copies < 1 || copies > MAX_COPIES) {
            throw new LabelPrintException("Copies must be 1 to " + MAX_COPIES + ".");
        }
        String problem = BarcodeValidator.problemWith(barcode);
        if (problem != null) {
            throw new LabelPrintException(problem);
        }
        PrintService service = findPrinter();
        byte[] data = toBytes(productName, barcode.trim(), copies);
        try {
            DocPrintJob job = service.createPrintJob();
            job.print(new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), null);
        } catch (PrintException e) {
            throw new LabelPrintException("The printer \"" + service.getName() + "\" did not accept the label. "
                    + "Check it is on and has paper, then print again.");
        }
    }

    /** The raw bytes for the print job (public so a check can verify the commands without a printer). */
    public static byte[] toBytes(String productName, String barcode, int copies) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);                                  // ESC @ : reset
        out.write(0x40);
        for (int i = 0; i < copies; i++) {
            out.write(0x1B);                              // ESC a 1 : centre
            out.write(0x61);
            out.write(1);
            writeText(out, shorten(productName));
            out.write(0x1D);                              // GS h 80 : bar height
            out.write(0x68);
            out.write(80);
            out.write(0x1D);                              // GS w 2 : bar width
            out.write(0x77);
            out.write(2);
            out.write(0x1D);                              // GS H 2 : print the number below the bars
            out.write(0x48);
            out.write(2);
            writeBarcode(out, barcode);
            out.write('\n');
            out.write(0x1B);                              // ESC d n : feed
            out.write(0x64);
            out.write(FEED_LINES);
        }
        return out.toByteArray();
    }

    private static void writeBarcode(ByteArrayOutputStream out, String barcode) {
        BarcodeValidator.Format format = BarcodeValidator.formatOf(barcode);
        out.write(0x1D);                                  // GS k m n d1..dn
        out.write(0x6B);
        switch (format) {
            case UPC_A -> out.write(65);
            case EAN_13 -> out.write(67);
            case EAN_8 -> out.write(68);
            case OTHER -> {
                out.write(73);                            // CODE128
                byte[] body = ("{B" + barcode).getBytes(StandardCharsets.US_ASCII);   // code set B
                out.write(body.length);
                out.write(body, 0, body.length);
                return;
            }
        }
        byte[] digits = barcode.getBytes(StandardCharsets.US_ASCII);
        out.write(digits.length);
        out.write(digits, 0, digits.length);
    }

    private static void writeText(ByteArrayOutputStream out, String text) {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
        out.write('\n');
    }

    private static String shorten(String name) {
        String n = name == null ? "" : name.trim();
        return n.length() <= NAME_WIDTH ? n : n.substring(0, NAME_WIDTH);
    }

    private static PrintService findPrinter() throws LabelPrintException {
        String wanted = System.getenv("NSC_PRINTER_NAME");
        if (wanted != null && !wanted.isBlank()) {
            for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
                if (s.getName().equalsIgnoreCase(wanted.trim())) {
                    return s;
                }
            }
            throw new LabelPrintException("There is no printer named \"" + wanted.trim() + "\" on this computer.");
        }
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        if (def == null) {
            throw new LabelPrintException("No printer is set up on this computer. Set a default printer first.");
        }
        return def;
    }
}
