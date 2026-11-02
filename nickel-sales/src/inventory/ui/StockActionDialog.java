package inventory.ui;

import inventory.model.StockReason;
import inventory.model.StockRow;
import inventory.service.InventoryException;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import shared.Quantity;
import shared.Theme;
import shared.UiErrors;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Stock In, Stock Out, Adjust Stock and Reorder Level windows (one class,
 * four modes). The dialog stays open until the service accepts the change, so
 * the service's own message (e.g. "Not enough stock for ...") is shown under the
 * fields. No rules live here: it only collects input and calls {@link Submit}.
 */
final class StockActionDialog {

    enum Mode { STOCK_IN, STOCK_OUT, ADJUST, REORDER }

    interface Submit {
        void run(BigDecimal quantity, StockReason reason, String note) throws InventoryException;
    }

    private StockActionDialog() { }

    /** @return true if the change was saved */
    static boolean show(Window owner, Mode mode, StockRow row, Submit submit) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(switch (mode) {
            case STOCK_IN -> "Stock In";
            case STOCK_OUT -> "Stock Out";
            case ADJUST -> "Adjust Stock";
            case REORDER -> "Reorder Level";
        });
        dialog.setHeaderText(row.name() + "   (" + Quantity.format(row.onHand()) + " " + row.unit() + " on hand)");

        Label qtyLabel = new Label(switch (mode) {
            case STOCK_IN -> "Quantity to add";
            case STOCK_OUT -> "Quantity to remove";
            case ADJUST -> "New counted quantity";
            case REORDER -> "Reorder level (Low Stock at or below this)";
        });
        qtyLabel.setStyle(labelStyle());
        TextField qty = new TextField(switch (mode) {
            case ADJUST -> Quantity.format(row.onHand());
            case REORDER -> Quantity.format(row.reorderLevel());
            default -> "";
        });
        qty.setStyle("-fx-font-size: 18px;");

        VBox content = new VBox(8, qtyLabel, qty);
        content.setPadding(new Insets(10));
        content.setMinWidth(420);

        ComboBox<StockReason> reason = new ComboBox<>();
        TextField note = new TextField();
        if (mode == Mode.STOCK_OUT || mode == Mode.ADJUST) {
            List<StockReason> reasons = mode == Mode.STOCK_OUT ? StockReason.forStockOut() : StockReason.forAdjustment();
            reason.getItems().setAll(reasons);
            reason.setPromptText("Choose a reason");
            reason.setMaxWidth(Double.MAX_VALUE);
            reason.setConverter(new StringConverter<>() {
                @Override
                public String toString(StockReason r) {
                    return r == null ? "" : r.label();
                }

                @Override
                public StockReason fromString(String s) {
                    return null;
                }
            });
            Label reasonLabel = new Label("Reason");
            reasonLabel.setStyle(labelStyle());
            content.getChildren().addAll(reasonLabel, reason);
        }
        if (mode != Mode.REORDER) {
            Label noteLabel = new Label(mode == Mode.STOCK_IN
                    ? "Reference / Note (optional, e.g. PO number)"
                    : "Note (required when the reason is Other)");
            noteLabel.setStyle(labelStyle());
            content.getChildren().addAll(noteLabel, note);
        }

        Label error = new Label();
        error.setWrapText(true);
        error.setMaxWidth(420);
        error.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + Theme.ERROR + ";");
        content.getChildren().add(error);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText(switch (mode) {
            case STOCK_IN -> "Add stock";
            case STOCK_OUT -> "Remove stock";
            case ADJUST -> "Save count";
            case REORDER -> "Save";
        });

        boolean[] saved = {false};
        // Consuming the event keeps the dialog open so the person can fix the input.
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            BigDecimal quantity = null;
            String text = qty.getText() == null ? "" : qty.getText().trim().replace(",", "");
            if (!text.isEmpty()) {
                try {
                    quantity = new BigDecimal(text);
                } catch (NumberFormatException ex) {
                    error.setText("Type the quantity as a number, e.g. 6 or 1.5.");
                    qty.requestFocus();
                    e.consume();
                    return;
                }
            }
            try {
                submit.run(quantity, reason.getValue(), note.getText());
                saved[0] = true;
            } catch (InventoryException ex) {
                error.setText(ex.getMessage());
                e.consume();
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "Something went wrong. Try again, or contact the system administrator."));
                e.consume();
            }
        });

        Platform.runLater(() -> {
            qty.requestFocus();
            qty.selectAll();
        });
        dialog.showAndWait();
        return saved[0];
    }

    private static String labelStyle() {
        return "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";";
    }
}
