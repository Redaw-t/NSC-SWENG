package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import pos.contract.SaleLineRecord;
import shared.Modal;
import shared.Money;
import shared.Quantity;
import shared.Styles;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Del on a line of a retrieved sale: how much of it to void (what is still left is filled in).
 * The red Void Item [ENTER] button confirms; Esc keeps the sale as it is.
 */
final class VoidItemDialog {

    private VoidItemDialog() { }

    /** @return the quantity to void (more than 0, at most {@code left}), or empty if closed */
    static Optional<BigDecimal> ask(Window owner, String invoiceNo, SaleLineRecord line, BigDecimal left) {
        Modal modal = new Modal(owner, 600);
        BigDecimal[] result = {null};

        TextField field = new TextField(Quantity.format(left));
        field.setPromptText("e.g. 1");
        Styles.underline(field);

        GridPane grid = Modal.grid();
        Modal.row(grid, 0, "Quantity to Void", field);

        Label refund = new Label();
        Styles.modalLabel(refund);
        Runnable showRefund = () -> {
            BigDecimal q = parse(field.getText(), left);
            refund.setText(q == null ? "" : "Refund " + PosScreen.peso(
                    q.compareTo(left) == 0 && left.compareTo(line.quantity()) == 0 ? line.lineTotal()
                            : SaleLineRecord.totalOf(q, line.unitPrice())) + " to the customer.");
        };
        field.textProperty().addListener((o, was, now) -> showRefund.run());
        showRefund.run();

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = PosStyle.modalKeyButton("Cancel", "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button confirm = PosStyle.modalKeyButton("Void Item", "ENTER", PosStyle.Kind.DANGER);
        confirm.setDefaultButton(true);
        confirm.setOnAction(e -> {
            BigDecimal q = parse(field.getText(), left);
            if (q == null) {
                error.setText("Type a number above 0, up to " + Quantity.format(left) + " " + line.unit() + ".");
                field.requestFocus();
                field.selectAll();
                return;
            }
            result[0] = q;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title("Void Item"),
                Modal.subtitle(line.productName() + "  ·  " + Quantity.format(left) + " " + line.unit()
                        + " left of " + Quantity.format(line.quantity()) + " @ " + Money.format(line.unitPrice())
                        + "  ·  Invoice " + invoiceNo),
                grid, refund, error, Modal.buttons(cancel, confirm));
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        modal.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    private static BigDecimal parse(String text, BigDecimal max) {
        try {
            BigDecimal q = Quantity.of(text);
            return q.signum() > 0 && q.compareTo(max) <= 0 ? q : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
