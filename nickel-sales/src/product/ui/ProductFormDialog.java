package product.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import product.model.Category;
import product.model.Product;
import product.model.ProductDraft;
import product.model.Supplier;
import product.service.ProductException;
import shared.Quantity;
import shared.UiErrors;

import java.math.BigDecimal;
import java.util.List;

/**
 * Add / edit product form. Stays open until the submit callback succeeds or the
 * user cancels, so a refused value (duplicate barcode, price too high, ...) can be
 * fixed without retyping the rest. Only text is read here; the rules are in
 * ProductService.
 */
final class ProductFormDialog {

    interface Submit {
        void accept(ProductDraft draft) throws ProductException;
    }

    /** The "(none)" choice in the category and supplier lists. */
    private static final Category NO_CATEGORY = new Category(null, "(none)", true);
    private static final Supplier NO_SUPPLIER = new Supplier(null, "(none)", null, null, null, null, true);

    private static final List<String> COMMON_UNITS =
            List.of("pc", "pack", "box", "bottle", "can", "roll", "ream", "set", "sack", "kg", "g", "L", "ml");

    private ProductFormDialog() { }

    /**
     * @param existing   null when adding a product
     * @param categories every category; deactivated ones are offered only if {@code existing} has one
     * @param suppliers  every supplier; deactivated ones are offered only if {@code existing} has one
     */
    static void show(Window owner, Product existing, List<Category> categories, List<Supplier> suppliers,
                     Submit submit) {
        boolean creating = existing == null;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(creating ? "Add Product" : "Edit Product");
        dialog.setHeaderText(creating ? "New products start with no stock; Inventory adds it."
                : existing.productId() + " - stock is changed by Inventory, not here.");

        TextField name = new TextField(creating ? "" : existing.name());
        name.setPromptText("Product name");
        TextField barcode = new TextField(creating || existing.barcode() == null ? "" : existing.barcode());
        barcode.setPromptText("Scan or type; leave empty if none");
        ComboBox<String> unit = new ComboBox<>();
        unit.getItems().addAll(COMMON_UNITS);
        unit.setEditable(true);
        unit.setValue(creating ? "pc" : existing.unit());
        TextField retail = new TextField(creating ? "" : existing.retailPrice().toPlainString());
        retail.setPromptText("Price before VAT");
        TextField wholesale = new TextField(creating || existing.wholesalePrice() == null
                ? "" : existing.wholesalePrice().toPlainString());
        wholesale.setPromptText("Optional");
        CheckBox vatable = new CheckBox("VAT applies (12%)");
        vatable.setSelected(creating || existing.vatable());

        ComboBox<Category> category = new ComboBox<>();
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

        ComboBox<Supplier> supplier = new ComboBox<>();
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

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints(260);
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        int row = 0;
        grid.addRow(row++, new Label("Name"), name);
        grid.addRow(row++, new Label("Barcode"), barcode);
        grid.addRow(row++, new Label("Unit"), unit);
        grid.addRow(row++, new Label("Retail price"), retail);
        grid.addRow(row++, new Label("Wholesale price"), wholesale);
        grid.addRow(row++, new Label(""), vatable);
        grid.addRow(row++, new Label("Category"), category);
        grid.addRow(row++, new Label("Supplier"), supplier);
        if (!creating) {
            grid.addRow(row, new Label("Stock on hand"), new Label(Quantity.format(existing.stockOnHand())
                    + " " + existing.unit()));
        }
        category.setMaxWidth(Double.MAX_VALUE);
        supplier.setMaxWidth(Double.MAX_VALUE);
        unit.setMaxWidth(Double.MAX_VALUE);

        Label error = new Label();
        error.setWrapText(true);
        error.setMaxWidth(380);
        error.setStyle("-fx-text-fill: #a61c1c;");

        VBox content = new VBox(10, grid, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText(creating ? "Create" : "Save");
        // Consuming the event keeps the dialog open so the input can be fixed.
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Category chosenCategory = category.getValue();
                Supplier chosenSupplier = supplier.getValue();
                submit.accept(new ProductDraft(
                        barcode.getText(),
                        name.getText(),
                        unit.getEditor().getText(),
                        price(retail.getText(), "retail price", true),
                        price(wholesale.getText(), "wholesale price", false),
                        vatable.isSelected(),
                        chosenCategory == null ? null : chosenCategory.categoryId(),
                        chosenSupplier == null ? null : chosenSupplier.supplierId()));
            } catch (ProductException e) {
                error.setText(e.getMessage());
                event.consume();
            } catch (RuntimeException e) {
                error.setText(UiErrors.message(e, "Something went wrong. Try again, or contact the system administrator."));
                event.consume();
            }
        });

        dialog.showAndWait();
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
}
