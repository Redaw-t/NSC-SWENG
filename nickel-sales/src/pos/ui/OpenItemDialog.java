package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import pos.cart.SalesService;
import product.contract.ProductUnits;
import shared.Modal;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * F2 Open Item: sell something that is not in the catalog. The cashier types a name, the price
 * (VAT included), the quantity, picks the unit (same list as Add Product) and whether it is VATable. The item is only added to this sale;
 * it is not saved as a product and moves no stock (see {@link SalesService#addOpenItem}).
 *
 * Enter adds it, Esc closes without adding. A refusal from the cart (e.g. a name that is too
 * long) is shown here and the dialog stays open.
 */
final class OpenItemDialog {

    /** What the cashier typed. */
    record Entry(String name, String unit, BigDecimal unitPrice, BigDecimal quantity, boolean vatable) { }

    private OpenItemDialog() { }

    /**
     * Shows the dialog. {@code submit} adds the item and returns null, or returns the reason it
     * was refused (shown in the dialog). Returns true if an item was added.
     */
    static boolean show(Window owner, BigDecimal startQuantity, Function<Entry, String> submit) {
        Modal modal = new Modal(owner, 600);
        boolean[] added = {false};

        TextField name = new TextField();
        name.setPromptText("e.g. Gift wrapping");
        Styles.underline(name);
        TextField price = new TextField();
        price.setPromptText("VAT included, e.g. 25.00");
        Styles.underline(price);
        TextField quantity = new TextField(Quantity.format(startQuantity));
        quantity.setPromptText("e.g. 1 or 1.5");
        Styles.underline(quantity);
        // Same unit drop-down as Add Product: pc / box / ream / pad, or type another in "Others".
        SearchPicker<String> unit = new SearchPicker<>(true, false);
        unit.getItems().addAll(ProductUnits.CHOICES);
        unit.allowCustom("Others", typed -> typed);
        unit.setPromptText("Select");
        unit.setValue(ProductUnits.DEFAULT);
        CheckBox vatable = new CheckBox("VATable (uncheck if VAT-exempt)");
        vatable.setSelected(true);
        Styles.modalCheckBox(vatable);

        GridPane grid = Modal.grid();
        int r = Modal.row(grid, 0, "Item Name", name);
        r = Modal.row(grid, r, "Unit Price (₱)", price);
        r = Modal.row(grid, r, "Quantity", quantity);
        r = Modal.row(grid, r, "Unit", unit);
        Modal.row(grid, r, "", vatable);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = PosStyle.modalKeyButton("Cancel", "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button add = PosStyle.modalKeyButton("Add Item", "ENTER", PosStyle.Kind.PRIMARY);
        add.setDefaultButton(true);
        add.setOnAction(e -> {
            if (name.getText() == null || name.getText().isBlank()) {
                fail(error, name, "Type the item's name.");
                return;
            }
            BigDecimal p = parsePrice(price.getText());
            if (p == null) {
                fail(error, price, "Type a price above 0 with at most 2 decimals, e.g. 25 or 25.50.");
                return;
            }
            BigDecimal q = parseQuantity(quantity.getText());
            if (q == null) {
                fail(error, quantity, "Type a quantity above 0, e.g. 1 or 1.5.");
                return;
            }
            String u = unit.getValue() == null ? "" : unit.getValue().trim();
            if (u.isEmpty()) {
                error.setText("Pick a unit, or type one under Others.");
                return;
            }
            String refused = submit.apply(new Entry(name.getText(), u, p, q, vatable.isSelected()));
            if (refused != null) {
                fail(error, name, refused);
                return;
            }
            added[0] = true;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title("Open Item"),
                Modal.subtitle("For items not in the product list. Only added to this sale; "
                        + "not saved as a product and no stock is used."),
                grid, error, Modal.buttons(cancel, add));
        Platform.runLater(name::requestFocus);
        modal.showAndWait();
        return added[0];
    }

    private static void fail(Label error, TextField field, String text) {
        error.setText(text);
        field.requestFocus();
        field.selectAll();
    }

    /** A price above 0 with at most 2 decimals ("1,250.50" is accepted); null if not. */
    private static BigDecimal parsePrice(String text) {
        try {
            BigDecimal p = new BigDecimal(text == null ? "" : text.trim().replace(",", ""));
            return p.signum() > 0 && Math.max(p.stripTrailingZeros().scale(), 0) <= 2 ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal parseQuantity(String text) {
        try {
            BigDecimal q = Quantity.of(text);
            return q.signum() > 0 ? q : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
