package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import pos.cart.SaleLineItem;
import shared.Modal;
import shared.Money;
import shared.Styles;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * F4 on a cart line: type a new unit price (VAT included) for this sale only. The product's own
 * price is not changed. Cancel [ESC] keeps the price; Apply [ENTER] changes it. A refusal from the
 * cart is shown here and the dialog stays open.
 */
final class EditPriceDialog {

    private EditPriceDialog() { }

    /**
     * {@code submit} applies the price and returns null, or returns why it was refused.
     * Returns true if the price was changed.
     */
    static boolean show(Window owner, SaleLineItem line, Function<BigDecimal, String> submit) {
        Modal modal = new Modal(owner, 580);
        boolean[] changed = {false};

        TextField field = new TextField(Money.format(line.unitPrice()).replace(",", ""));
        field.setPromptText("VAT included, e.g. 25.00");
        Styles.underline(field);

        GridPane grid = Modal.grid();
        Modal.row(grid, 0, "New Unit Price (₱)", field);

        Label note = new Label("For this sale only. The product's price is not changed.");
        note.setWrapText(true);
        Styles.muted(note);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = PosStyle.modalKeyButton("Cancel", "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button apply = PosStyle.modalKeyButton("Apply", "ENTER", PosStyle.Kind.PRIMARY);
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            BigDecimal price = parse(field.getText());
            if (price == null) {
                error.setText("Type a price of 0 or more with at most 2 decimals, e.g. 25 or 25.50.");
                field.requestFocus();
                field.selectAll();
                return;
            }
            String refused = submit.apply(price);
            if (refused != null) {
                error.setText(refused);
                field.requestFocus();
                field.selectAll();
                return;
            }
            changed[0] = true;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title("Edit Price"),
                Modal.subtitle(line.productName() + "  (now " + PosScreen.peso(line.unitPrice())
                        + " per " + line.unit() + ")"),
                grid, note, error, Modal.buttons(cancel, apply));
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        modal.showAndWait();
        return changed[0];
    }

    /** 0 or more, at most 2 decimals ("1,250.50" is accepted); null if not. */
    private static BigDecimal parse(String text) {
        try {
            BigDecimal p = new BigDecimal(text == null ? "" : text.trim().replace(",", ""));
            return p.signum() >= 0 && Math.max(p.stripTrailingZeros().scale(), 0) <= 2 ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
