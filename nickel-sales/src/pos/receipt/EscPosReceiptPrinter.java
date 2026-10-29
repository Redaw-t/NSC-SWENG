package pos.receipt;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Prints receipts straight to a thermal printer with ESC/POS commands, like the
 * printer maker's own test print: no page, no margins, no extra paper.
 *
 * Uses the Windows printer named in the environment variable NSC_PRINTER_NAME
 * (e.g. POS-58), or the default printer when it is not set.
 * Text only (plain ASCII; anything else prints as '?').
 */
public class EscPosReceiptPrinter implements PrinterPort {

    /** Blank lines fed after the receipt so the last line clears the tear bar. 2-4 is usual. */
    private static final int FEED_LINES = 3;

    private final int width;

    /** @param width characters per line (32 for 58 mm paper) */
    public EscPosReceiptPrinter(int width) {
        this.width = width;
    }

    @Override
    public void printReceipt(Receipt receipt) throws ReceiptPrintException {
        PrintService service = findPrinter();
        byte[] data = toBytes(receipt.toText(width));
        try {
            DocPrintJob job = service.createPrintJob();
            job.print(new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), null);
        } catch (PrintException e) {
            throw new ReceiptPrintException("The printer \"" + service.getName() + "\" did not accept the receipt. "
                    + "Check it is on and has paper, then print again.");
        }
    }

    private static PrintService findPrinter() throws ReceiptPrintException {
        String wanted = System.getenv("NSC_PRINTER_NAME");
        if (wanted != null && !wanted.isBlank()) {
            for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
                if (s.getName().equalsIgnoreCase(wanted.trim())) {
                    return s;
                }
            }
            throw new ReceiptPrintException("There is no printer named \"" + wanted.trim() + "\" on this computer.");
        }
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        if (def == null) {
            throw new ReceiptPrintException("No printer is set up on this computer. Set a default printer in Windows.");
        }
        return def;
    }

    private static byte[] toBytes(List<String> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);                       // ESC @  : reset the printer
        out.write(0x40);
        for (String line : lines) {
            byte[] text = line.getBytes(StandardCharsets.US_ASCII);   // non-ASCII becomes '?'
            out.write(text, 0, text.length);
            out.write('\n');
        }
        out.write(0x1B);                       // ESC d n : feed n lines
        out.write(0x64);
        out.write(FEED_LINES);
        return out.toByteArray();
    }
}