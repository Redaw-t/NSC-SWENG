package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import pos.cart.SaleLineItem;
import shared.Modal;
import shared.Quantity;
import shared.Styles;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * F3 / Enter on a cart line: type the new quantity, Enter to apply, Esc to keep it. Drawn with the
 * shared {@link Modal}, like the pop-ups of the other screens.
 */
final class QuantityDialog {

    private QuantityDialog() { }

    static Optional<BigDecimal> ask(Window owner, SaleLineItem line) {
        Modal modal = new Modal(owner, 560);
        BigDecimal[] result = {null};

        TextField field = new TextField(Quantity.format(line.quantity()));
        field.setPromptText("e.g. 6 or 1.5");
        Styles.underline(field);

        GridPane grid = Modal.grid();
        Modal.row(grid, 0, "New Quantity", field);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = PosStyle.modalKeyButton("Cancel", "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button apply = PosStyle.modalKeyButton("Apply", "ENTER", PosStyle.Kind.PRIMARY);
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            BigDecimal q = parse(field.getText());
            if (q == null) {
                error.setText("Type a number above 0, e.g. 6 or 1.5.");
                field.requestFocus();
                field.selectAll();
                return;
            }
            result[0] = q;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title("Change Quantity"),
                Modal.subtitle(line.productName() + "  (now " + Quantity.format(line.quantity()) + " " + line.unit() + ")"),
                grid, error, Modal.buttons(cancel, apply));
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        modal.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    private static BigDecimal parse(String text) {
        try {
            BigDecimal q = Quantity.of(text);
            return q.signum() > 0 ? q : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
