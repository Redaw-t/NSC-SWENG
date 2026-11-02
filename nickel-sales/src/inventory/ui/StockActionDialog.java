package inventory.ui;

import inventory.model.StockReason;
import inventory.model.StockRow;
import inventory.service.InventoryException;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
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
 * four modes), laid out like INVENTORY_UI.pdf: Product dropdown, Current Stock,
 * quantity, reason, note. The dialog stays open until the service accepts the
 * change. No rules live here: it only collects input and calls {@link Submit}.
 */
final class StockActionDialog {

    enum Mode { STOCK_IN, STOCK_OUT, ADJUST, REORDER }

    interface Submit {
        void run(String productId, BigDecimal quantity, StockReason reason, String note) throws InventoryException;
    }

    private StockActionDialog() { }

    /**
     * @param rows     products to choose from
     * @param selected product to preselect (may be null)
     * @return the ID of the product that was changed, or null if cancelled
     */
    static String show(Window owner, Mode mode, List<StockRow> rows, StockRow selected, Submit submit) {
        String title = switch (mode) {
            case STOCK_IN -> "Stock In";
            case STOCK_OUT -> "Stock Out";
            case ADJUST -> "Adjust Stock";
            case REORDER -> "Reorder Level";
        };
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);

        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");

        ComboBox<StockRow> product = new ComboBox<>(FXCollections.observableArrayList(rows));
        product.setPromptText("Select product");
        product.setMaxWidth(Double.MAX_VALUE);
        product.setConverter(new StringConverter<>() {
            @Override
            public String toString(StockRow r) {
                return r == null ? "" : r.name() + "  (" + (r.barcode() == null ? r.productId() : r.barcode()) + ")";
            }

            @Override
            public StockRow fromString(String s) {
                return null;
            }
        });

        Label current = new Label("-");
        current.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        TextField qty = new TextField();
        qty.setStyle("-fx-font-size: 16px;");
        ComboBox<StockReason> reason = new ComboBox<>();
        TextField note = new TextField();

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(170);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(280);
        grid.getColumnConstraints().addAll(c0, c1);

        int r = 0;
        addRow(grid, r++, "Product", product);
        addRow(grid, r++, "Current Stock", current);
        addRow(grid, r++, switch (mode) {
            case STOCK_IN -> "Quantity to Add";
            case STOCK_OUT -> "Quantity to Remove";
            case ADJUST -> "New Quantity";
            case REORDER -> "Reorder Level";
        }, qty);
        if (mode == Mode.STOCK_OUT || mode == Mode.ADJUST) {
            List<StockReason> reasons = mode == Mode.STOCK_OUT ? StockReason.forStockOut() : StockReason.forAdjustment();
            reason.getItems().setAll(reasons);
            reason.setPromptText("Choose a reason");
            reason.setMaxWidth(Double.MAX_VALUE);
            reason.setConverter(new StringConverter<>() {
                @Override
                public String toString(StockReason x) {
                    return x == null ? "" : x.label();
                }

                @Override
                public StockReason fromString(String s) {
                    return null;
                }
            });
            addRow(grid, r++, "Reason", reason);
        }
        if (mode != Mode.REORDER) {
            addRow(grid, r++, switch (mode) {
                case STOCK_IN -> "Reference / Note";
                case STOCK_OUT -> "Note";
                default -> "Adjustment Note";
            }, note);
        }

        product.valueProperty().addListener((o, was, row) -> {
            if (row == null) {
                current.setText("-");
                return;
            }
            current.setText(Quantity.format(row.onHand()) + " " + row.unit());
            if (mode == Mode.ADJUST) {
                qty.setText(Quantity.format(row.onHand()));
            } else if (mode == Mode.REORDER) {
                qty.setText(Quantity.format(row.reorderLevel()));
            }
        });
        if (selected != null) {
            rows.stream().filter(x -> x.productId().equals(selected.productId())).findFirst()
                    .ifPresent(product::setValue);
        }

        Label error = new Label();
        error.setWrapText(true);
        error.setMaxWidth(460);
        error.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + Theme.ERROR + ";");

        VBox content = new VBox(16, heading, new Separator(), grid, error);
        content.setPadding(new Insets(8));
        content.setMinWidth(500);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText(switch (mode) {
            case STOCK_IN -> "Confirm Stock In";
            case STOCK_OUT -> "Confirm Stock Out";
            case ADJUST -> "Save Adjustment";
            case REORDER -> "Save";
        });
        ok.setStyle("-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-text-fill: white; -fx-font-weight: bold; "
                + "-fx-background-radius: 8; -fx-padding: 10 24 10 24;");
        Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancel.setStyle("-fx-background-color: #DDE1EA; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; "
                + "-fx-background-radius: 8; -fx-padding: 10 24 10 24;");

        String[] savedId = {null};
        // Consuming the event keeps the dialog open so the person can fix the input.
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            StockRow chosen = product.getValue();
            if (chosen == null) {
                error.setText("Select a product.");
                e.consume();
                return;
            }
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
                submit.run(chosen.productId(), quantity, reason.getValue(), note.getText());
                savedId[0] = chosen.productId();
            } catch (InventoryException ex) {
                error.setText(ex.getMessage());
                e.consume();
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "Something went wrong. Try again, or contact the system administrator."));
                e.consume();
            }
        });

        Platform.runLater(() -> {
            if (product.getValue() == null) {
                product.requestFocus();
            } else {
                qty.requestFocus();
                qty.selectAll();
            }
        });
        dialog.showAndWait();
        return savedId[0];
    }

    private static void addRow(GridPane grid, int row, String label, Node control) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        grid.add(l, 0, row);
        grid.add(control, 1, row);
    }
}
