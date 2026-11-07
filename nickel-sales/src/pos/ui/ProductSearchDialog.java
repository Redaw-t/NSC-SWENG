package pos.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.cart.ThresholdPricingRule;
import product.contract.ProductInfo;
import product.contract.ProductLookup;
import shared.Modal;
import shared.Quantity;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.util.List;
import java.util.Optional;

/**
 * F1 on the POS screen: find a product by name (for items without a barcode, or when the barcode
 * won't scan). Type to search, Down / Up to choose, Enter to add, Esc to close. Only products still
 * sold are listed. Drawn with the shared {@link Modal}.
 */
final class ProductSearchDialog {

    private static final int MAX_RESULTS = 30;
    /** Height of one product row, and how many rows the list shows at once. */
    private static final double ROW_HEIGHT = 56;
    private static final int VISIBLE_ROWS = 6;

    private ProductSearchDialog() { }

    /** The chosen product, or empty if the cashier closed the pop-up. */
    static Optional<ProductInfo> pick(Window owner, ProductLookup products, String startText) {
        Modal modal = new Modal(owner, 780);
        ProductInfo[] chosen = {null};

        TextField query = new TextField(startText);
        query.setPromptText("Type part of the name or barcode, e.g. glue");
        Styles.input(query);

        ListView<ProductInfo> list = new ListView<>();
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPrefHeight(VISIBLE_ROWS * ROW_HEIGHT);   // whole rows only: no half-cut row at the top or bottom
        list.setMinHeight(VISIBLE_ROWS * ROW_HEIGHT);
        list.setMaxHeight(VISIBLE_ROWS * ROW_HEIGHT);
        Styles.pickerList(list);
        list.setCellFactory(v -> new ProductCell());
        list.setPlaceholder(new Label("No products found."));

        Label status = new Label();
        Styles.muted(status);

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
                list.scrollTo(0);
            }
            status.setText(found.isEmpty() ? "No products found." : found.size() + " found"
                    + (found.size() == MAX_RESULTS ? " (showing the first " + MAX_RESULTS + ")" : "")
                    + ".  Prices include VAT.");
        };
        query.textProperty().addListener((o, was, is) -> search.run());
        search.run();

        Button cancel = PosStyle.modalKeyButton("Cancel", "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button add = PosStyle.modalKeyButton("Add to Sale", "ENTER", PosStyle.Kind.PRIMARY);
        add.setDefaultButton(true);
        add.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        Runnable take = () -> {
            chosen[0] = list.getSelectionModel().getSelectedItem();
            if (chosen[0] != null) {
                modal.close();
            }
        };
        add.setOnAction(e -> take.run());

        // Down / Up in the search box move the selection; Enter (the default button) adds it.
        query.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                int i = list.getSelectionModel().getSelectedIndex() + (e.getCode() == KeyCode.DOWN ? 1 : -1);
                if (i >= 0 && i < list.getItems().size()) {
                    list.getSelectionModel().select(i);
                    reveal(list, i);
                }
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                take.run();
            }
        });

        Label keys = new Label("↑ ↓ choose   ·   double-click to add");   // Esc / Enter are on the buttons
        Styles.muted(keys);
        modal.card().getChildren().addAll(Modal.title("Find a Product"), query, list, status, keys,
                Modal.buttons(cancel, add));
        Platform.runLater(() -> {
            query.requestFocus();
            query.end();
        });
        modal.showAndWait();
        return Optional.ofNullable(chosen[0]);
    }

    /**
     * Scrolls just enough to show row {@code index} in full: nothing if it is already on screen, one
     * row at the edges. (ListView.scrollTo would put the row at the TOP every time, so the list jumped.)
     */
    private static void reveal(ListView<?> list, int index) {
        if (list.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
            flow.scrollTo(index);
        } else {
            list.scrollTo(index);   // not laid out yet
        }
    }

    /** Name and details on the left, the price on the right; tinted under the mouse or when chosen. */
    private static final class ProductCell extends ListCell<ProductInfo> {

        ProductCell() {
            hoverProperty().addListener((o, was, is) -> paint());
            selectedProperty().addListener((o, was, is) -> paint());
        }

        @Override
        protected void updateItem(ProductInfo p, boolean empty) {
            super.updateItem(p, empty);
            setText(null);
            if (empty || p == null) {
                setGraphic(null);
                paint();
                return;
            }
            Label name = new Label(p.name());
            name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
            Label details = new Label(Quantity.format(p.stockOnHand()) + " " + p.unit() + " left   ·   "
                    + (p.barcode() == null ? "no barcode" : p.barcode()));
            details.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            VBox left = new VBox(2, name, details);

            Label price = new Label(PosScreen.peso(p.retailPrice()));
            price.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
            VBox right = new VBox(2, price);
            right.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            if (p.hasWholesalePrice()) {
                Label wholesale = new Label(PosScreen.peso(p.wholesalePrice()) + " from "
                        + ThresholdPricingRule.WHOLESALE_MIN_QTY);
                wholesale.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
                right.getChildren().add(wholesale);
            }
            Region fill = new Region();
            HBox.setHgrow(fill, Priority.ALWAYS);
            HBox row = new HBox(12, left, fill, right);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setGraphic(row);
            paint();
        }

        private void paint() {
            double tint = isEmpty() ? 0 : isSelected() ? 0.14 : isHover() ? 0.08 : 0;
            Styles.pickerCell(this, tint);
        }
    }
}
