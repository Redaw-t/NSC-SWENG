package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import pos.contract.SaleHistory;
import pos.contract.SaleRecord;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.util.Locale;
import java.util.Optional;

/**
 * F6 on the POS screen: type an invoice number (the last one is filled in) and Enter to find the
 * saved sale for a reprint. Esc closes. Drawn with the shared {@link Modal}.
 */
final class ReprintDialog {

    private ReprintDialog() { }

    static Optional<SaleRecord> ask(Window owner, SaleHistory history, String lastInvoice) {
        Modal modal = new Modal(owner, 600);
        SaleRecord[] found = {null};

        TextField invoice = new TextField(lastInvoice == null ? "" : lastInvoice);
        invoice.setPromptText("e.g. T01-2026-000123");
        Styles.underline(invoice);

        GridPane grid = Modal.grid();
        Modal.row(grid, 0, "Invoice No.", invoice);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button find = new Button("Find");
        Styles.modalButton(find, Theme.INFORMATION, "white");
        find.setDefaultButton(true);
        find.setOnAction(e -> {
            String no = invoice.getText() == null ? "" : invoice.getText().trim().toUpperCase(Locale.ROOT);
            if (no.isEmpty()) {
                error.setText("Type the invoice number.");
                return;
            }
            SaleRecord sale;
            try {
                sale = history.findByInvoiceNo(no).orElse(null);
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "Sales could not be searched. Try again."));
                return;
            }
            if (sale == null) {
                error.setText("No sale has the invoice number " + no + ".");
                invoice.selectAll();
                return;
            }
            found[0] = sale;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title("Reprint Receipt"),
                Modal.subtitle("Type the invoice number of the sale."), grid, error, Modal.buttons(cancel, find));
        Platform.runLater(() -> {
            invoice.requestFocus();
            invoice.selectAll();
        });
        modal.showAndWait();
        return Optional.ofNullable(found[0]);
    }
}
