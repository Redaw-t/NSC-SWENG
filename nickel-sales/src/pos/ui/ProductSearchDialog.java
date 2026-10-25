package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.cart.ThresholdPricingRule;
import product.contract.ProductInfo;
import product.contract.ProductLookup;
import shared.Quantity;
import shared.UiErrors;

import java.util.List;
import java.util.Optional;

/**
 * F3 on the POS screen: find a product by name (for items without a barcode,
 * or when the barcode won't scan). Type to search, Down / Up to choose,
 * Enter to add, Esc to close. Only products still sold are listed.
 */
final class ProductSearchDialog {

    private static final int MAX_RESULTS = 30;

    private ProductSearchDialog() { }

    /** The chosen product, or empty if the cashier closed the dialog. */
    static Optional<ProductInfo> pick(Window owner, ProductLookup products, String startText) {
        Dialog<ProductInfo> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Find a product");
        dialog.setHeaderText("Type part of the name (or barcode). ↓ to choose, Enter to add.\nPrices are before VAT.");

        TextField query = new TextField(startText);
        query.setPromptText("e.g. glue");
        query.setStyle("-fx-font-size: 18px;");
        ListView<ProductInfo> list = new ListView<>();
        list.setPrefSize(640, 360);
        list.setStyle("-fx-font-size: 15px;");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(ProductInfo p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : describe(p));
            }
        });
        Label status = new Label();
        status.setStyle("-fx-text-fill: #555555;");

        Runnable search = () -> {
            List<ProductInfo> found;
            try {
                found = products.search(query.getText().trim(), MAX_RESULTS);
            } catch (RuntimeException e) {
                list.getItems().clear();
                status.setText(UiErrors.message(e, "Products could not be searched. Try again."));
                return;
            }
            list.getItems().setAll(found);
            if (!found.isEmpty()) {
                list.getSelectionModel().select(0);
            }
            status.setText(found.isEmpty() ? "No products found." : found.size() + " found"
                    + (found.size() == MAX_RESULTS ? " (showing the first " + MAX_RESULTS + ")" : ""));
        };
        query.textProperty().addListener((o, was, is) -> search.run());
        search.run();

        ButtonType add = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(10, query, list, status));
        dialog.getDialogPane().lookupButton(add).disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());

        // Down/Up in the search box move the selection; Enter anywhere adds it.
        query.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                int i = list.getSelectionModel().getSelectedIndex() + (e.getCode() == KeyCode.DOWN ? 1 : -1);
                if (i >= 0 && i < list.getItems().size()) {
                    list.getSelectionModel().select(i);
                    list.scrollTo(i);
                }
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(list.getSelectionModel().getSelectedItem());
                dialog.close();
            }
        });
        dialog.setResultConverter(b -> b == add ? list.getSelectionModel().getSelectedItem() : null);
        Platform.runLater(() -> {
            query.requestFocus();
            query.end();
        });
        return dialog.showAndWait();
    }

    private static String describe(ProductInfo p) {
        StringBuilder s = new StringBuilder(p.name()).append("   ").append(PosScreen.peso(p.retailPrice()));
        if (p.hasWholesalePrice()) {
            s.append("  (").append(PosScreen.peso(p.wholesalePrice())).append(" from ")
                    .append(ThresholdPricingRule.WHOLESALE_MIN_QTY).append(")");
        }
        s.append("   ·  ").append(Quantity.format(p.stockOnHand())).append(" ").append(p.unit()).append(" left");
        s.append(p.barcode() == null ? "   ·  no barcode" : "   ·  " + p.barcode());
        return s.toString();
    }
}
