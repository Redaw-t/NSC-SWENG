package pos.receipt;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import shared.PrinterSettings;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Prints receipts straight to a thermal printer with ESC/POS commands, like the printer maker's own
 * test print: no page, no margins, no extra paper.
 *
 * Uses the receipt printer chosen in Settings > Printers; if none is chosen, the printer named in
 * the environment variable NSC_PRINTER_NAME (e.g. POS-58), else the Windows default printer.
 *
 * The whole receipt (logo, text, barcode) is drawn by {@link ReceiptRenderer} and sent as a picture
 * (GS v 0, in strips so small printers can keep up), never with the printer's own fonts. So it
 * prints exactly like the on-screen preview, on any ESC/POS printer.
 */
public class EscPosReceiptPrinter implements PrinterPort {

    /** Blank lines fed after the receipt so the last line clears the tear bar. 2-4 is usual. */
    private static final int FEED_LINES = 4;
    /** Rows per picture strip: small enough for any printer's buffer. */
    private static final int STRIP_ROWS = 128;

    private final int width;

    /** @param width the receipt width setting (32 for 58 mm paper, 42-48 for 80 mm) */
    public EscPosReceiptPrinter(int width) {
        this.width = width;
    }

    @Override
    public void printReceipt(Receipt receipt) throws ReceiptPrintException {
        send(toBytes(ReceiptRenderer.render(receipt, width)));
    }

    /**
     * Every receipt in one print job, back to back (each with its own reset and feed, as if printed on
     * its own): the printer gets a single job, so nothing waits in the queue behind the first receipt.
     */
    @Override
    public void printReceipts(java.util.List<Receipt> receipts) throws ReceiptPrintException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (Receipt r : receipts) {
            all.writeBytes(toBytes(ReceiptRenderer.render(r, width)));
        }
        send(all.toByteArray());
    }

    /** One print job with {@code data} on the receipt printer. */
    private static void send(byte[] data) throws ReceiptPrintException {
        PrintService service = findPrinter();
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

    /** The ESC/POS bytes for a rendered receipt (public so a check can verify them without a printer). */
    public static byte[] toBytes(BufferedImage picture) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);                       // ESC @  : reset the printer
        out.write(0x40);
        out.write(0x1B);                       // ESC a 1 : centre the picture on the paper
        out.write(0x61);
        out.write(1);
        int w = picture.getWidth();
        int widthBytes = (w + 7) / 8;
        for (int top = 0; top < picture.getHeight(); top += STRIP_ROWS) {
            int rows = Math.min(STRIP_ROWS, picture.getHeight() - top);
            out.write(0x1D);                   // GS v 0 m xL xH yL yH d1...dk
            out.write(0x76);
            out.write(0x30);
            out.write(0);
            out.write(widthBytes & 0xFF);
            out.write(widthBytes >> 8);
            out.write(rows & 0xFF);
            out.write(rows >> 8);
            byte[] row = new byte[widthBytes];
            for (int y = top; y < top + rows; y++) {
                java.util.Arrays.fill(row, (byte) 0);
                for (int x = 0; x < w; x++) {
                    if ((picture.getRGB(x, y) & 0xFFFFFF) == 0) {   // black dot
                        row[x / 8] |= (byte) (0x80 >> (x % 8));
                    }
                }
                out.write(row, 0, row.length);
            }
        }
        out.write(0x1B);                       // ESC d n : feed n lines
        out.write(0x64);
        out.write(FEED_LINES);
        return out.toByteArray();
    }
}
