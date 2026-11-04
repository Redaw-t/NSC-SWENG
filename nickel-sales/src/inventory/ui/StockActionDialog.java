package inventory.ui;

import inventory.model.StockReason;
import inventory.model.StockRow;
import inventory.service.InventoryException;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import shared.Modal;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Stock In, Stock Out, Adjust Stock and Reorder Level pop-ups (one class, four modes), drawn
 * with the shared {@link Modal} like the Product screens.
 *
 * <p>Opened from a table row ({@code lockProduct} true) the product is fixed and shown as text.
 * Opened from the toolbar it is chosen with a {@link SearchPicker} (type a name or barcode).
 * No rules live here: it only collects input and calls {@link Submit}. It stays open until the
 * service accepts the change.
 */
final class StockActionDialog {

    enum Mode { STOCK_IN, STOCK_OUT, ADJUST, REORDER }

    interface Submit {
        void run(String productId, BigDecimal quantity, StockReason reason, String note) throws InventoryException;
    }

    /** A list item for {@link SearchPicker}, which shows (and searches) {@code toString()}. */
    private record Choice<T>(T item, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private StockActionDialog() { }

    /**
     * @param rows        products to choose from (used only when {@code lockProduct} is false)
     * @param selected    product to preselect; required when {@code lockProduct} is true
     * @param lockProduct true when opened from a table row: no product drop-down
     * @return the ID of the product that was changed, or null if cancelled
     */
    static String show(Window owner, Mode mode, List<StockRow> rows, StockRow selected, boolean lockProduct,
                       Submit submit) {
        boolean locked = lockProduct && selected != null;
        Modal modal = new Modal(owner, 640);

        String title = switch (mode) {
            case STOCK_IN -> "Stock In";
            case STOCK_OUT -> "Stock Out";
            case ADJUST -> "Adjust Stock";
            case REORDER -> "Reorder Level";
        };

        // --- product: fixed text (row button) or a searchable picker (toolbar) ---
        StockRow[] chosen = {locked ? selected : null};
        Label current = value("-");
        TextField qty = new TextField();
        Styles.underline(qty);
        qty.setPromptText(switch (mode) {
            case STOCK_IN, STOCK_OUT -> "e.g. 6 or 1.5";
            case ADJUST -> "Counted quantity";
            case REORDER -> "0 = not set";
        });

        Runnable showChosen = () -> {
            StockRow row = chosen[0];
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
        };

        Node productField;
        SearchPicker<Choice<StockRow>> picker = null;
        if (locked) {
            productField = value(describe(selected));
        } else {
            picker = new SearchPicker<>(true);
            picker.setPromptText("Select product");
            Choice<StockRow> preselect = null;
            for (StockRow r : rows) {
                Choice<StockRow> c = new Choice<>(r, describe(r));
                picker.getItems().add(c);
                if (selected != null && r.productId().equals(selected.productId())) {
                    preselect = c;
                }
            }
            picker.valueProperty().addListener((o, was, is) -> {
                chosen[0] = is == null ? null : is.item();
                showChosen.run();
            });
            picker.setValue(preselect);
            productField = picker;
        }
        showChosen.run();

        // --- reason (Stock Out / Adjust) ---
        SearchPicker<Choice<StockReason>> reason = null;
        if (mode == Mode.STOCK_OUT || mode == Mode.ADJUST) {
            reason = new SearchPicker<>(true, false);
            reason.setPromptText("Choose a reason");
            List<StockReason> reasons = mode == Mode.STOCK_OUT ? StockReason.forStockOut() : StockReason.forAdjustment();
            for (StockReason r : reasons) {
                reason.getItems().add(new Choice<>(r, r.label()));
            }
        }

        TextField note = new TextField();
        Styles.underline(note);
        note.setPromptText(mode == Mode.STOCK_IN ? "Invoice / PO no. or note (optional)" : "Optional");

        GridPane grid = Modal.grid();
        int r = 0;
        r = Modal.row(grid, r, "Product", productField);
        r = Modal.row(grid, r, "Current Stock", current);
        r = Modal.row(grid, r, switch (mode) {
            case STOCK_IN -> "Quantity to Add";
            case STOCK_OUT -> "Quantity to Remove";
            case ADJUST -> "New Quantity";
            case REORDER -> "Reorder Level";
        }, qty);
        if (reason != null) {
            r = Modal.row(grid, r, "Reason", reason);
        }
        if (mode != Mode.REORDER) {
            Modal.row(grid, r, switch (mode) {
                case STOCK_IN -> "Reference / Note";
                case STOCK_OUT -> "Note";
                default -> "Adjustment Note";
            }, note);
        }

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());

        Button save = new Button(switch (mode) {
            case STOCK_IN -> "Confirm Stock In";
            case STOCK_OUT -> "Confirm Stock Out";
            case ADJUST -> "Save Adjustment";
            case REORDER -> "Save";
        });
        Styles.modalButton(save, mode == Mode.STOCK_OUT ? Theme.ACCENT_RED : Theme.INFORMATION, "white");
        save.setDefaultButton(true);

        String[] savedId = {null};
        SearchPicker<Choice<StockReason>> reasonPicker = reason;
        save.setOnAction(e -> {
            StockRow row = chosen[0];
            if (row == null) {
                error.setText("Select a product.");
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
                    return;
                }
            }
            StockReason why = reasonPicker == null || reasonPicker.getValue() == null
                    ? null : reasonPicker.getValue().item();
            try {
                submit.run(row.productId(), quantity, why, note.getText());
                savedId[0] = row.productId();
                modal.close();
            } catch (InventoryException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "Something went wrong. Try again, or contact the system administrator."));
            }
        });

        String subtitle = switch (mode) {
            case STOCK_IN -> "Adds stock received from a supplier.";
            case STOCK_OUT -> "Removes stock that left without a sale (damaged, expired, returned...).";
            case ADJUST -> "Sets the stock to what was actually counted.";
            case REORDER -> "The product shows as Low Stock at or below this level.";
        };
        modal.card().getChildren().addAll(Modal.title(title), Modal.subtitle(subtitle), grid, error,
                Modal.buttons(cancel, save));

        Node first = chosen[0] == null && picker != null ? picker : qty;
        Platform.runLater(() -> {
            first.requestFocus();
            if (first == qty) {
                qty.selectAll();
            }
        });
        modal.showAndWait();
        return savedId[0];
    }

    /** "Bond Paper A4  (4800123456789)". */
    static String describe(StockRow r) {
        return r.name() + "  (" + r.barcodeOrId() + ")";
    }

    private static Label value(String text) {
        Label label = new Label(text);
        Styles.modalValue(label);
        label.setWrapText(true);
        return label;
    }
}