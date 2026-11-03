package product.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import product.model.Product;
import shared.Modal;
import shared.Money;
import shared.Quantity;
import shared.Styles;
import shared.Theme;

/** The "View" pop-up: every field of one product, read only. */
final class ProductDetailsDialog {

    private ProductDetailsDialog() { }

    static void show(Window owner, Product p) {
        Modal modal = new Modal(owner, 700);

        GridPane grid = Modal.grid();
        int row = 0;
        row = Modal.row(grid, row, "Product Name", value(p.name()));
        row = Modal.row(grid, row, "Category", value(p.categoryName() == null ? "(none)" : p.categoryName()));
        row = Modal.row(grid, row, "Supplier", value(p.supplierName() == null ? "(none)" : p.supplierName()));
        row = Modal.row(grid, row, "Retail Price", value("\u20B1 " + Money.format(p.retailPrice())));
        row = Modal.row(grid, row, "Wholesale Price",
                value(p.wholesalePrice() == null ? "(none)" : "\u20B1 " + Money.format(p.wholesalePrice())));
        row = Modal.row(grid, row, "Barcode", value(p.barcode() == null ? "(none)" : p.barcode()));
        row = Modal.row(grid, row, "Unit", value(p.unit()));
        row = Modal.row(grid, row, "VAT", value(p.vatable() ? "12%" : "Exempt"));
        row = Modal.row(grid, row, "Stock on Hand", value(Quantity.format(p.stockOnHand()) + " " + p.unit()));
        Modal.row(grid, row, "Status", ProductUi.pill(p.active()));

        Button close = new Button("Close");
        Styles.modalButton(close, Theme.INFORMATION, "white");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title("Product Details"), grid, Modal.buttons(close));
        modal.showAndWait();
    }

    private static Label value(String text) {
        Label label = new Label(text);
        Styles.modalValue(label);
        label.setWrapText(true);
        return label;
    }
}
