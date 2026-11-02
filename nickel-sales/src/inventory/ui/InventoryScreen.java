package inventory.ui;

import app.ScreenContext;
import inventory.model.StockMovement;
import inventory.model.StockRow;
import inventory.model.StockStatus;
import inventory.service.InventoryException;
import inventory.service.InventoryService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import shared.PhTime;
import shared.Quantity;
import shared.Theme;
import shared.UiErrors;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Inventory screen (style: docs/FRONTEND_STYLE_GUIDE.md section 17).
 *
 * Summary cards (Total / Low / Out of stock), then two tabs:
 *  - Current Stock: search + status filter, table with status chips, and the
 *    Stock In / Stock Out / Adjust Stock / Reorder Level actions for the selected row;
 *  - Stock Movement History: newest first, searchable.
 *
 * Only calls {@link InventoryService}; no SQL and no stock rules here.
 * Esc goes back to the home screen.
 */
public final class InventoryScreen {

    private static final String ALL = "All statuses";
    private static final int HISTORY_LIMIT = 500;

    private static final String CARD = "-fx-background-color: " + Theme.SURFACE + "; -fx-background-radius: 12; "
            + "-fx-border-color: " + Theme.BORDER + "; -fx-border-radius: 12; -fx-padding: 16 20 16 20;";
    private static final String PRIMARY = "-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-text-fill: white; "
            + "-fx-font-size: 15px; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 10 20 10 20; -fx-cursor: hand;";
    private static final String SECONDARY = "-fx-background-color: white; -fx-text-fill: " + Theme.PRIMARY_BLUE + "; "
            + "-fx-font-size: 15px; -fx-font-weight: bold; -fx-border-color: " + Theme.PRIMARY_BLUE + "; "
            + "-fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 9 20 9 20; -fx-cursor: hand;";
    private static final String FIELD = "-fx-background-color: white; -fx-background-radius: 8; -fx-border-color: "
            + Theme.BORDER + "; -fx-border-radius: 8; -fx-font-size: 15px; -fx-padding: 8 12 8 12;";

    private final ScreenContext screen;
    private final InventoryService inventory;

    private final BorderPane view = new BorderPane();
    private final ObservableList<StockRow> rows = FXCollections.observableArrayList();
    private final FilteredList<StockRow> shownRows = new FilteredList<>(rows, r -> true);
    private final ObservableList<StockMovement> movements = FXCollections.observableArrayList();
    private final FilteredList<StockMovement> shownMovements = new FilteredList<>(movements, m -> true);
    private final Map<String, String> names = new HashMap<>();

    private final TableView<StockRow> stockTable = new TableView<>();
    private final TableView<StockMovement> historyTable = new TableView<>();
    private final TextField search = new TextField();
    private final TextField historySearch = new TextField();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final Label totalValue = new Label("0");
    private final Label lowValue = new Label("0");
    private final Label outValue = new Label("0");
    private final Label message = new Label();

    public InventoryScreen(ScreenContext screen, InventoryService inventory) {
        this.screen = screen;
        this.inventory = inventory;
        buildView();
        reload(null);
    }

    public Parent getView() {
        return view;
    }

    /* ------------------------------ data ---------------------------------- */

    private void reload(String keepProductId) {
        try {
            List<StockRow> loaded = inventory.listStock(screen.sessionId());
            List<StockMovement> history = inventory.history(screen.sessionId(), HISTORY_LIMIT);
            names.clear();
            loaded.forEach(r -> names.put(r.productId(), r.name()));
            rows.setAll(loaded);
            movements.setAll(history);
            updateCards();
            if (keepProductId != null) {
                for (StockRow r : rows) {
                    if (r.productId().equals(keepProductId)) {
                        stockTable.getSelectionModel().select(r);
                        stockTable.scrollTo(r);
                    }
                }
            }
        } catch (InventoryException e) {
            showError(e.getMessage());
        } catch (RuntimeException e) {
            showError(UiErrors.message(e, "Inventory could not be loaded. Try again."));
        }
    }

    private void updateCards() {
        long low = rows.stream().filter(r -> r.status() == StockStatus.LOW_STOCK).count();
        long out = rows.stream().filter(r -> r.status() == StockStatus.OUT_OF_STOCK).count();
        totalValue.setText(String.valueOf(rows.size()));
        lowValue.setText(String.valueOf(low));
        outValue.setText(String.valueOf(out));
    }

    private void act(StockActionDialog.Mode mode) {
        StockRow row = stockTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        String id = row.productId();
        boolean saved = StockActionDialog.show(screen.stage(), mode, row, (qty, reason, note) -> {
            String session = screen.sessionId();
            switch (mode) {
                case STOCK_IN -> inventory.stockIn(session, id, qty, note);
                case STOCK_OUT -> inventory.stockOut(session, id, qty, reason, note);
                case ADJUST -> inventory.adjust(session, id, qty, reason, note);
                case REORDER -> inventory.setReorderLevel(session, id, qty);
            }
        });
        if (saved) {
            reload(id);
            rows.stream().filter(r -> r.productId().equals(id)).findFirst().ifPresent(r ->
                    showInfo(r.name() + ": " + Quantity.format(r.onHand()) + " " + r.unit() + " on hand."));
        }
    }

    /* ------------------------------ layout -------------------------------- */

    private void buildView() {
        Label title = new Label("Inventory");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label subtitle = new Label("Current stock, reorder levels and stock movement history");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button back = new Button("Home  (Esc)");
        back.setStyle(SECONDARY);
        back.setFocusTraversable(false);
        back.setOnAction(e -> screen.goHome());
        HBox top = new HBox(16, new VBox(4, title, subtitle), spacer, back);
        top.setAlignment(Pos.CENTER_LEFT);

        HBox cards = new HBox(16,
                card("Total Products", totalValue, Theme.PRIMARY_TEXT),
                card("Low Stock", lowValue, Theme.WARNING),
                card("Out of Stock", outValue, Theme.ERROR));

        TabPane tabs = new TabPane(
                new Tab("Current Stock", buildStockTab()),
                new Tab("Stock Movement History", buildHistoryTab()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(16, top, cards, tabs, message);
        content.setPadding(new Insets(24));
        view.setCenter(content);
        view.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + "; -fx-font-family: '" + Theme.FONT_FAMILY + "';");

        view.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                screen.goHome();
                e.consume();
            }
        });
    }

    private Parent buildStockTab() {
        search.setPromptText("Search by product name or barcode");
        search.setStyle(FIELD);
        HBox.setHgrow(search, Priority.ALWAYS);
        statusFilter.getItems().addAll(ALL, StockStatus.IN_STOCK.label(), StockStatus.LOW_STOCK.label(),
                StockStatus.OUT_OF_STOCK.label());
        statusFilter.setValue(ALL);
        statusFilter.setStyle(FIELD);
        search.textProperty().addListener((o, was, is) -> applyStockFilter());
        statusFilter.valueProperty().addListener((o, was, is) -> applyStockFilter());

        Button stockIn = action("Stock In", PRIMARY, StockActionDialog.Mode.STOCK_IN);
        Button stockOut = action("Stock Out", SECONDARY, StockActionDialog.Mode.STOCK_OUT);
        Button adjust = action("Adjust Stock", SECONDARY, StockActionDialog.Mode.ADJUST);
        Button reorder = action("Reorder Level", SECONDARY, StockActionDialog.Mode.REORDER);
        Button refresh = new Button("Refresh");
        refresh.setStyle(SECONDARY);
        refresh.setOnAction(e -> reload(selectedId()));

        HBox filters = new HBox(12, search, statusFilter, refresh);
        filters.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(12, stockIn, stockOut, adjust, reorder);
        Label hint = new Label("Select a product, then choose an action.");
        hint.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        actions.getChildren().add(hint);
        actions.setAlignment(Pos.CENTER_LEFT);

        stockTable.setItems(shownRows);
        stockTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        stockTable.setPlaceholder(new Label("No products found."));
        stockTable.setStyle("-fx-font-size: 14px;");
        TableColumn<StockRow, String> status = col("Status", 120, r -> r.status().label(), "CENTER_LEFT");
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                if (empty || text == null) {
                    setGraphic(null);
                    return;
                }
                String[] colors = chipColors(text);
                Label chip = new Label(text);   // readable text, never colour alone
                chip.setStyle("-fx-background-color: " + colors[0] + "; -fx-text-fill: " + colors[1] + "; "
                        + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 3 12 3 12;");
                setGraphic(chip);
            }
        });
        stockTable.getColumns().add(col("Product", 240, StockRow::name, "CENTER_LEFT"));
        stockTable.getColumns().add(col("Barcode", 140, r -> r.barcode() == null ? "No barcode" : r.barcode(), "CENTER_LEFT"));
        stockTable.getColumns().add(col("Current Stock", 120, r -> Quantity.format(r.onHand()) + " " + r.unit(), "CENTER_RIGHT"));
        stockTable.getColumns().add(col("Reorder Level", 120,
                r -> r.reorderLevel().signum() == 0 ? "Not set" : Quantity.format(r.reorderLevel()), "CENTER_RIGHT"));
        stockTable.getColumns().add(status);
        VBox.setVgrow(stockTable, Priority.ALWAYS);

        VBox box = new VBox(12, filters, actions, stockTable);
        box.setPadding(new Insets(16));
        return box;
    }

    private Parent buildHistoryTab() {
        historySearch.setPromptText("Search by product, type, reason or reference");
        historySearch.setStyle(FIELD);
        HBox.setHgrow(historySearch, Priority.ALWAYS);
        historySearch.textProperty().addListener((o, was, is) -> applyHistoryFilter());

        historyTable.setItems(shownMovements);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        historyTable.setPlaceholder(new Label("No stock movements yet."));
        historyTable.setStyle("-fx-font-size: 14px;");
        historyTable.getColumns().add(col("Date", 150, m -> PhTime.format(m.createdAt()), "CENTER_LEFT"));
        historyTable.getColumns().add(col("Product", 200, m -> names.getOrDefault(m.productId(), m.productId()), "CENTER_LEFT"));
        historyTable.getColumns().add(col("Type", 90, m -> m.type().label(), "CENTER_LEFT"));
        historyTable.getColumns().add(col("Quantity", 90,
                m -> (m.quantity().signum() > 0 ? "+" : "") + Quantity.format(m.quantity()), "CENTER_RIGHT"));
        historyTable.getColumns().add(col("Before", 80, m -> Quantity.format(m.quantityBefore()), "CENTER_RIGHT"));
        historyTable.getColumns().add(col("After", 80, m -> Quantity.format(m.quantityAfter()), "CENTER_RIGHT"));
        historyTable.getColumns().add(col("Reason", 130, m -> m.reason() == null ? "" : m.reason().label(), "CENTER_LEFT"));
        historyTable.getColumns().add(col("Reference / Note", 180, InventoryScreen::referenceText, "CENTER_LEFT"));
        historyTable.getColumns().add(col("By", 90, m -> m.performedBy() == null ? "POS sale" : m.performedBy(), "CENTER_LEFT"));
        VBox.setVgrow(historyTable, Priority.ALWAYS);

        VBox box = new VBox(12, historySearch, historyTable);
        box.setPadding(new Insets(16));
        return box;
    }

    /* ------------------------------ helpers ------------------------------- */

    private void applyStockFilter() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String st = statusFilter.getValue();
        shownRows.setPredicate(r ->
                (q.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(q)
                        || r.productId().toLowerCase(Locale.ROOT).contains(q)
                        || (r.barcode() != null && r.barcode().contains(q)))
                        && (st == null || ALL.equals(st) || st.equals(r.status().label())));
    }

    private void applyHistoryFilter() {
        String q = historySearch.getText() == null ? "" : historySearch.getText().trim().toLowerCase(Locale.ROOT);
        shownMovements.setPredicate(m -> q.isEmpty()
                || names.getOrDefault(m.productId(), m.productId()).toLowerCase(Locale.ROOT).contains(q)
                || m.type().label().toLowerCase(Locale.ROOT).contains(q)
                || (m.reason() != null && m.reason().label().toLowerCase(Locale.ROOT).contains(q))
                || referenceText(m).toLowerCase(Locale.ROOT).contains(q));
    }

    private Button action(String text, String style, StockActionDialog.Mode mode) {
        Button b = new Button(text);
        b.setStyle(style);
        b.disableProperty().bind(stockTable.getSelectionModel().selectedItemProperty().isNull());
        b.setOnAction(e -> act(mode));
        return b;
    }

    private VBox card(String name, Label value, String color) {
        Label label = new Label(name);
        label.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        value.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        VBox box = new VBox(4, label, value);
        box.setStyle(CARD);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private String selectedId() {
        StockRow r = stockTable.getSelectionModel().getSelectedItem();
        return r == null ? null : r.productId();
    }

    private static String referenceText(StockMovement m) {
        String ref = m.referenceNo() == null ? "" : m.referenceNo();
        String note = m.note() == null ? "" : m.note();
        return ref.isEmpty() ? note : note.isEmpty() ? ref : ref + " - " + note;
    }

    /** {background, text} for a status chip. */
    private static String[] chipColors(String label) {
        if (label.equals(StockStatus.IN_STOCK.label())) {
            return new String[] {"#E3F4EA", Theme.SUCCESS};
        }
        if (label.equals(StockStatus.LOW_STOCK.label())) {
            return new String[] {"#FBF1DC", "#9A6A0A"};
        }
        return new String[] {"#FBE4E4", Theme.ERROR};
    }

    private void showError(String text) {
        message.setText(text);
        message.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + Theme.ERROR + ";");
    }

    private void showInfo(String text) {
        message.setText(text);
        message.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.SUCCESS + ";");
    }

    private static <T> TableColumn<T, String> col(String name, double width, Function<T, String> value, String align) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setReorderable(false);
        c.setStyle("-fx-alignment: " + align + ";");
        return c;
    }
}
