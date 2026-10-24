package pos.receipt;

import javafx.print.PageLayout;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.text.Text;
import javafx.stage.Window;

import java.util.List;

/**
 * Prints receipts through JavaFX printing, so any printer Windows knows
 * works: an 80 mm / 58 mm receipt printer, or a normal printer.
 *
 * The text is sized to fill the paper's printable width (never above 11 pt,
 * so on A4 it stays receipt-sized), and split over several pages if the
 * paper is shorter than the receipt. Call from the JavaFX thread.
 */
public class JavaFxReceiptPrinter implements PrinterPort {

    private static final double MAX_FONT = 11;
    private static final double MIN_FONT = 5;

    private final int width;

    /** @param width characters per line, e.g. Receipt.DEFAULT_WIDTH */
    public JavaFxReceiptPrinter(int width) {
        this.width = width;
    }

    @Override
    public void printReceipt(Receipt receipt) throws ReceiptPrintException {
        Printer printer = Printer.getDefaultPrinter();
        if (printer == null) {
            throw new ReceiptPrintException("No printer is set up on this computer. Set a default printer in "
                    + "Windows, or use \"Choose printer\".");
        }
        PrinterJob job = PrinterJob.createPrinterJob(printer);
        if (job == null) {
            throw new ReceiptPrintException("The printer \"" + printer.getName() + "\" can't be used right now.");
        }
        print(job, receipt);
    }

    /**
     * Lets the cashier pick the printer (and paper) first.
     *
     * @return false if they closed the printer window without printing
     */
    public boolean printWithChoice(Receipt receipt, Window owner) throws ReceiptPrintException {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            throw new ReceiptPrintException("No printer is set up on this computer.");
        }
        if (!job.showPrintDialog(owner)) {
            job.cancelJob();
            return false;
        }
        print(job, receipt);
        return true;
    }

    private void print(PrinterJob job, Receipt receipt) throws ReceiptPrintException {
        List<String> lines = receipt.toText(width);
        PageLayout page = job.getJobSettings().getPageLayout();
        double printableWidth = page.getPrintableWidth();
        double printableHeight = page.getPrintableHeight();

        double fontSize = Math.min(MAX_FONT, 10 * printableWidth / ReceiptView.widthOf(width, 10));
        if (fontSize < MIN_FONT) {
            job.cancelJob();
            throw new ReceiptPrintException("The paper is too narrow for the receipt.");
        }
        int linesPerPage = Math.max(1, (int) Math.floor(printableHeight / ReceiptView.lineHeight(fontSize)) - 1);

        for (int from = 0; from < lines.size(); from += linesPerPage) {
            Text pageText = ReceiptView.text(lines.subList(from, Math.min(lines.size(), from + linesPerPage)), fontSize);
            if (!job.printPage(page, pageText)) {
                job.cancelJob();
                throw new ReceiptPrintException("The printer did not accept the receipt. Check it is on and "
                        + "has paper, then print again.");
            }
        }
        if (!job.endJob()) {
            throw new ReceiptPrintException("The receipt could not be finished on the printer. Print again.");
        }
    }
}
