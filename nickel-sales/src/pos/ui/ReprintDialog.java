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
        return ask(owner, history, lastInvoice, "Reprint Receipt", "Type the invoice number of the sale.", "Find");
    }

    /**
     * The same pop-up with its own title, grey line and button text (F7 Retrieve Transaction uses it).
     *
     * @return the completed sale with that invoice number, or empty if closed
     */
    static Optional<SaleRecord> ask(Window owner, SaleHistory history, String lastInvoice,
                                    String title, String subtitle, String buttonText) {
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

        Button cancel = PosStyle.modalKeyButton("Cancel", "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button find = PosStyle.modalKeyButton(buttonText, "ENTER", PosStyle.Kind.PRIMARY);
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
            invoice.clear();
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title(title), Modal.subtitle(subtitle), grid, error,
                Modal.buttons(cancel, find));
        Platform.runLater(() -> {
            invoice.requestFocus();
            invoice.selectAll();
        });
        modal.showAndWait();
        return Optional.ofNullable(found[0]);
    }
}
