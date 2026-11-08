package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import pos.contract.ReturnHistory;
import pos.contract.ReturnRecord;
import pos.contract.SaleHistory;
import pos.contract.SaleRecord;
import shared.Modal;
import shared.Styles;
import shared.UiErrors;

import java.util.Locale;
import java.util.Optional;

/**
 * F6 / F7 on the POS screen: type or SCAN an invoice number (the last one is filled in) and Enter
 * to find the saved sale. A return number (e.g. T01-2026-000123-01, scanned from the barcode on a RETURN slip)
 * finds that void and the sale it came from. Esc closes. Drawn with the shared {@link Modal}.
 */
final class ReprintDialog {

    /** What was found: the sale, and the void when a return number was typed / scanned (else null). */
    record Found(SaleRecord sale, ReturnRecord ret) { }

    private ReprintDialog() { }

    static Optional<Found> ask(Window owner, SaleHistory history, ReturnHistory returns, String lastInvoice) {
        return ask(owner, history, returns, lastInvoice, "Reprint Receipt",
                "Type or scan the invoice or return number.", "Find");
    }

    /**
     * The same pop-up with its own title, grey line and button text (F7 Retrieve Transaction uses it).
     *
     * @return the sale (and void) with that number, or empty if closed
     */
    static Optional<Found> ask(Window owner, SaleHistory history, ReturnHistory returns, String lastInvoice,
                               String title, String subtitle, String buttonText) {
        Modal modal = new Modal(owner, 600);
        Found[] found = {null};

        TextField invoice = new TextField(lastInvoice == null ? "" : lastInvoice);
        invoice.setPromptText("e.g. T01-2026-000123 or T01-2026-000123-01");
        Styles.underline(invoice);

        GridPane grid = Modal.grid();
        Modal.row(grid, 0, "Invoice / Return No.", invoice);

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
                error.setText("Type or scan the invoice or return number.");
                return;
            }
            Found result;
            try {
                result = lookUp(history, returns, no);
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "Sales could not be searched. Try again."));
                return;
            }
            if (result == null) {
                error.setText(ReturnRecord.isReturnNo(no)
                        ? "No void has the return number " + no + "."
                        : "No sale has the invoice number " + no + ".");
                invoice.selectAll();
                return;
            }
            found[0] = result;
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

    /**
     * The sale for an invoice number, or the void and its sale for a return number (invoice number +
     * "-01" ..., or a RETURN slip's barcode, which holds it without dashes); null if there is none.
     *
     * @throws RuntimeException database problem
     */
    static Found lookUp(SaleHistory history, ReturnHistory returns, String number) {
        String no = number.trim().toUpperCase(Locale.ROOT);
        if (ReturnRecord.isReturnNo(no)) {
            return returnAndSale(history, returns, no);
        }
        if (ReturnRecord.mayBeReturnBarcode(no)) {   // T01202600000101: a return if one has that number
            Found found = returnAndSale(history, returns, no);
            if (found != null) {
                return found;
            }
        }
        return history.findByInvoiceNo(no).map(sale -> new Found(sale, null)).orElse(null);
    }

    /** The return with this number (with or without dashes) and its sale; null if there is none. */
    static Found returnAndSale(SaleHistory history, ReturnHistory returns, String no) {
        ReturnRecord ret = returns.findByReturnNo(no).orElse(null);
        if (ret == null) {
            return null;
        }
        return history.findById(ret.saleId()).map(sale -> new Found(sale, ret)).orElse(null);
    }
}
