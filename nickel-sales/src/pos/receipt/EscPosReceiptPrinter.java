package pos.receipt;

import barcode.service.Code128;
import barcode.service.EscPosBarcode;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import shared.PrinterSettings;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Prints receipts straight to a thermal printer with ESC/POS commands, like the
 * printer maker's own test print: no page, no margins, no extra paper.
 *
 * Uses the receipt printer chosen in Settings > Printers; if none is chosen, the printer named in
 * the environment variable NSC_PRINTER_NAME (e.g. POS-58), else the Windows default printer.
 * Text is plain ASCII (anything else prints as '?'). The invoice-number barcode is sent
 * as a raster picture (GS v 0), which 58 mm and 80 mm ESC/POS printers support.
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
        byte[] data = toBytes(receipt.toParts(width), width);
        try {
            DocPrintJob job = service.createPrintJob();
            job.print(new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), null);
        } catch (PrintException e) {
            throw new ReceiptPrintException("The printer \"" + service.getName() + "\" did not accept the receipt. "
                    + "Check it is on and has paper, then print again.");
        }
    }

    /** The printer chosen in Settings > Printers, else NSC_PRINTER_NAME, else the Windows default. */
    private static PrintService findPrinter() throws ReceiptPrintException {
        try {
            return PrinterSettings.find(PrinterSettings.Use.RECEIPT);
        } catch (PrinterSettings.NoPrinterException e) {
            throw new ReceiptPrintException(e.getMessage());
        }
    }

    private static byte[] toBytes(List<Receipt.Part> parts, int width) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);                       // ESC @  : reset the printer
        out.write(0x40);
        for (Receipt.Part part : parts) {
            if (part instanceof Receipt.TextBlock block) {
                for (String line : block.lines()) {
                    byte[] text = line.getBytes(StandardCharsets.US_ASCII);   // non-ASCII becomes '?'
                    out.write(text, 0, text.length);
                    out.write('\n');
                }
            } else if (part instanceof Receipt.BarcodeBlock code) {
                boolean[] modules = Code128.modules(code.value());
                byte[] picture = EscPosBarcode.raster(modules, ReceiptBarcode.moduleDots(code.value(), width),
                        ReceiptBarcode.HEIGHT_DOTS);
                out.write(0x1B);               // ESC a 1 : center, so the paper margins are the quiet zone
                out.write(0x61);
                out.write(1);
                out.write(picture, 0, picture.length);
                out.write(0x1B);               // ESC a 0 : back to left
                out.write(0x61);
                out.write(0);
            }
        }
        out.write(0x1B);                       // ESC d n : feed n lines
        out.write(0x64);
        out.write(FEED_LINES);
        return out.toByteArray();
    }
}
