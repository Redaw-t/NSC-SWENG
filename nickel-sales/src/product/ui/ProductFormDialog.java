package product.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import product.model.Category;
import product.model.Product;
import product.model.ProductDraft;
import product.model.Supplier;
import product.service.ProductException;
import shared.Modal;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Add Product / Edit Product pop-up. It stays open until the submit callback succeeds
 * or the user cancels, so a refused value (duplicate barcode, price too high, ...) can be
 * fixed without retyping the rest. Only text is read here; the rules are in ProductService.
 *
 * Stock and Active/Inactive are not edited here: Inventory owns stock, and the table's
 * Deactivate / Reactivate button changes the status.
 */
final class ProductFormDialog {

    interface Submit {
        void accept(ProductDraft draft) throws ProductException;
    }

    /** The "(none)" choice in the category and supplier lists. */
    private static final Category NO_CATEGORY = new Category(null, "(none)", true);
    private static final Supplier NO_SUPPLIER = new Supplier(null, "(none)", null, null, null, null, true);

    /** The units offered in the drop-down; any other unit is typed in the "Others" field inside the list. */
    private static final List<String> UNITS = List.of("pc", "box", "ream", "pad");
    /** Barcodes made by this store start with 200: GS1 keeps 200-299 for in-store use, so no manufacturer has it. */
    private static final String STORE_PREFIX = "200";

    private ProductFormDialog() { }

    /**
     * @param existing   null when adding a product
     * @param categories every category; deactivated ones are offered only if {@code existing} has one
     * @param suppliers  every supplier; deactivated ones are offered only if {@code existing} has one
     */
    static void show(Window owner, Product existing, List<Category> categories, List<Supplier> suppliers,
                     Submit submit) {
        boolean creating = existing == null;
        Modal modal = new Modal(owner, 700);

        TextField name = new TextField(creating ? "" : existing.name());
        name.setPromptText("Product name");
        Styles.underline(name);

        SearchPicker<Category> category = new SearchPicker<>(true);
        category.getItems().add(NO_CATEGORY);
        Category currentCategory = NO_CATEGORY;
        for (Category c : categories) {
            boolean isCurrent = !creating && c.categoryId().equals(existing.categoryId());
            if (c.active() || isCurrent) {
                category.getItems().add(c);
            }
            if (isCurrent) {
                currentCategory = c;
            }
        }
        category.setValue(currentCategory);

        SearchPicker<Supplier> supplier = new SearchPicker<>(true);
        supplier.getItems().add(NO_SUPPLIER);
        Supplier currentSupplier = NO_SUPPLIER;
        for (Supplier s : suppliers) {
            boolean isCurrent = !creating && s.supplierId().equals(existing.supplierId());
            if (s.active() || isCurrent) {
                supplier.getItems().add(s);
            }
            if (isCurrent) {
                currentSupplier = s;
            }
        }
        supplier.setValue(currentSupplier);

        TextField retail = new TextField(creating ? "" : existing.retailPrice().toPlainString());
        retail.setPromptText("Price, VAT included");
        Styles.underline(retail);
        TextField wholesale = new TextField(creating || existing.wholesalePrice() == null
                ? "" : existing.wholesalePrice().toPlainString());
        wholesale.setPromptText("Optional");
        Styles.underline(wholesale);

        TextField barcode = new TextField(creating || existing.barcode() == null ? "" : existing.barcode());
        barcode.setPromptText("Scan or type; leave empty if none");
        Styles.underline(barcode);
        // A scanner types the barcode and presses Enter: move on to the product name instead of saving.
        barcode.setOnAction(e -> {
            name.requestFocus();
            name.selectAll();
        });
        Button generate = new Button("Generate");
        Styles.smallPillButton(generate);
        generate.setOnAction(e -> barcode.setText(newBarcode()));
        HBox barcodeRow = new HBox(8, barcode, generate);
        barcodeRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(barcode, Priority.ALWAYS);

        // Unit: pick pc / box / ream / pad, or type another unit in the "Others" field inside the list.
        SearchPicker<String> unitPicker = new SearchPicker<>(true, false);
        unitPicker.getItems().addAll(UNITS);
        unitPicker.allowCustom("Others", typed -> typed);
        unitPicker.setPromptText("Select");
        unitPicker.setValue(creating ? "pc" : existing.unit());

        CheckBox vatable = new CheckBox("VAT applies (12%)");
        vatable.setSelected(creating || existing.vatable());
        Styles.modalCheckBox(vatable);

        GridPane grid = Modal.grid();
        int row = 0;
        row = Modal.row(grid, row, "Barcode", barcodeRow);
        row = Modal.row(grid, row, "Product Name", name);
        row = Modal.row(grid, row, "Category", category);
        row = Modal.row(grid, row, "Supplier", supplier);
        row = Modal.row(grid, row, "Retail Price", pesoField(retail));
        row = Modal.row(grid, row, "Wholesale Price", pesoField(wholesale));
        row = Modal.row(grid, row, "Unit", unitPicker);
        Modal.row(grid, row, "VAT", vatable);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button save = new Button(creating ? "Save Product" : "Save Changes");
        Styles.modalButton(save, Theme.INFORMATION, "white");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            try {
                Category chosenCategory = category.getValue();
                Supplier chosenSupplier = supplier.getValue();
                submit.accept(new ProductDraft(
                        barcode.getText(),
                        name.getText(),
                        unitPicker.getValue() == null ? "" : unitPicker.getValue(),
                        price(retail.getText(), "retail price", true),
                        price(wholesale.getText(), "wholesale price", false),
                        vatable.isSelected(),
                        chosenCategory == null ? null : chosenCategory.categoryId(),
                        chosenSupplier == null ? null : chosenSupplier.supplierId()));
                modal.close();
            } catch (ProductException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        String note = creating
                ? "New products start with no stock; Inventory adds it."
                : "Stock on hand: " + Quantity.format(existing.stockOnHand()) + " " + existing.unit()
                        + ". Stock is changed by Inventory, not here.";
        modal.card().getChildren().addAll(Modal.title(creating ? "Add Product" : "Edit Product"),
                Modal.subtitle(note), grid, error, Modal.buttons(cancel, save));
        modal.showAndWait();
    }

    /** A price field with the peso sign in front. */
    private static HBox pesoField(TextField field) {
        Label peso = new Label("\u20B1");
        peso.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        HBox box = new HBox(6, peso, field);
        box.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    /** Turns what was typed ("1,250.50") into a number; the rules (not negative, ...) are the service's. */
    private static BigDecimal price(String text, String label, boolean required) throws ProductException {
        String typed = text == null ? "" : text.trim().replace(",", "");
        if (typed.isEmpty()) {
            if (required) {
                throw new ProductException("Enter the " + label + ".");
            }
            return null;
        }
        try {
            return new BigDecimal(typed);
        } catch (NumberFormatException e) {
            throw new ProductException("The " + label + " must be a number, for example 149.50.");
        }
    }

    /** A store-only EAN-13: the 200 prefix, 9 random digits and a correct check digit. */
    private static String newBarcode() {
        StringBuilder digits = new StringBuilder(STORE_PREFIX);
        for (int i = 0; i < 9; i++) {
            digits.append(ThreadLocalRandom.current().nextInt(10));
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return digits.append((10 - sum % 10) % 10).toString();
    }
}
