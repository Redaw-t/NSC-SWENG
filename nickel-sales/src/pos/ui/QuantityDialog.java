package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.cart.SaleLineItem;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.Optional;

/** F4 / Enter on a cart line: type the new quantity, Enter to apply, Esc to keep it. */
final class QuantityDialog {

    private QuantityDialog() { }

    static Optional<BigDecimal> ask(Window owner, SaleLineItem line) {
        Dialog<BigDecimal> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Change quantity");
        dialog.setHeaderText(line.productName() + "  (now " + Quantity.format(line.quantity()) + " " + line.unit() + ")");

        TextField field = new TextField(Quantity.format(line.quantity()));
        field.setStyle("-fx-font-size: 20px;");
        Label error = new Label();
        error.setStyle("-fx-text-fill: " + PosScreen.RED + ";");
        dialog.getDialogPane().setContent(new VBox(8, new Label("New quantity (e.g. 6 or 1.5):"), field, error));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Keep the dialog open until the text is a number above 0.
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            BigDecimal q = parse(field.getText());
            if (q == null) {
                error.setText("Type a number above 0, e.g. 6 or 1.5.");
                field.requestFocus();
                field.selectAll();
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.OK ? parse(field.getText()) : null);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog.showAndWait();
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
