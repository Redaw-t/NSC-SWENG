package pos.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.contract.SaleHistory;
import pos.contract.SaleRecord;
import shared.UiErrors;

import java.util.Optional;

/**
 * F7 on the POS screen: type an invoice number (the last one is filled in)
 * and Enter to find the saved sale for a reprint. Esc closes.
 */
final class ReprintDialog {

    private ReprintDialog() { }

    static Optional<SaleRecord> ask(Window owner, SaleHistory history, String lastInvoice) {
        Dialog<SaleRecord> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Reprint a receipt");
        dialog.setHeaderText("Invoice number of the sale, e.g. T01-2026-000123");

        TextField invoice = new TextField(lastInvoice == null ? "" : lastInvoice);
        invoice.setStyle("-fx-font-size: 20px;");
        invoice.setPrefColumnCount(18);
        Label error = new Label();
        error.setWrapText(true);
        error.setMaxWidth(420);
        error.setStyle("-fx-font-weight: bold; -fx-text-fill: " + PosScreen.RED + ";");
        dialog.getDialogPane().setContent(new VBox(10, invoice, error));

        ButtonType find = new ButtonType("Find", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(find, ButtonType.CANCEL);

        SaleRecord[] found = new SaleRecord[1];
        dialog.getDialogPane().lookupButton(find).addEventFilter(ActionEvent.ACTION, e -> {
            String no = invoice.getText().trim().toUpperCase();
            if (no.isEmpty()) {
                error.setText("Type the invoice number.");
                e.consume();
                return;
            }
            try {
                found[0] = history.findByInvoiceNo(no).orElse(null);
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "Sales could not be searched. Try again."));
                e.consume();
                return;
            }
            if (found[0] == null) {
                error.setText("No sale has the invoice number " + no + ".");
                invoice.selectAll();
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == find ? found[0] : null);
        Platform.runLater(() -> {
            invoice.requestFocus();
            invoice.selectAll();
        });
        return dialog.showAndWait();
    }
}
