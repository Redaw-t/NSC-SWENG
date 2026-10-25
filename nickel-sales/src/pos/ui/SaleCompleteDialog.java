package pos.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.SaleRecord;
import pos.receipt.PrinterPort;
import pos.receipt.Receipt;

/**
 * Shown after a sale is saved: the change to hand back (big) and the receipt
 * preview. Enter prints the receipt; Esc closes without printing. Either way
 * the next sale starts afterwards.
 */
final class SaleCompleteDialog {

    private SaleCompleteDialog() { }

    static void show(Window owner, SaleRecord sale, Receipt receipt, PrinterPort printer, int width) {
        PaymentRecord p = sale.payments().get(0);
        Label top;
        if (p.method() == PaymentMethod.CASH) {
            top = new Label("CHANGE  " + PosScreen.peso(p.change()));
            top.setStyle("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.GREEN + ";");
        } else {
            top = new Label("Paid by " + PaymentService.label(p.method()) + "  ·  Ref. " + p.referenceNo());
            top.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.GREEN + ";");
        }
        Label total = new Label("Total " + PosScreen.peso(sale.total())
                + (p.method() == PaymentMethod.CASH ? "   ·   Cash " + PosScreen.peso(p.amountTendered()) : ""));
        total.setStyle("-fx-font-size: 15px;");
        ReceiptDialog.show(owner, "Sale complete", "Sale complete  ·  Invoice " + sale.invoiceNo(),
                new VBox(4, top, total), receipt, printer, width);
    }
}
