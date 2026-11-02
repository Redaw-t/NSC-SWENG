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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import shared.PhTime;
import shared.Quantity;
import shared.Theme;
import shared.UiErrors;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Inventory screen, laid out like INVENTORY_UI.pdf (style: docs/FRONTEND_STYLE_GUIDE.md section 17).
 *
 * Two tabs (Current Stock / Stock Movement History) share one toolbar (search, status, category,
 * Stock In / Stock Out / Adjust Stock), a 20-per-page table with a pager, and the Total / Low /
 * Out of stock boxes at the bottom.
 *
 * Only calls {@link InventoryService}; no SQL and no stock rules here. Esc goes back home.
 */
public final class InventoryScreen {

    private static final String ALL = "Stock Status";
    private static final int HISTORY_LIMIT = 500;
    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d/yy", Locale.US);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);

    private static final String TABLE_CSS = String.join("\n",
            ".nsc-table { -fx-background-color: white; -fx-border-color: " + Theme.PRIMARY_BLUE + "; -fx-border-width: 2; "
                    + "-fx-table-cell-border-color: #C9CFD9; }",
            ".nsc-table .column-header-background { -fx-background-color: " + Theme.PRIMARY_BLUE + "; }",
            ".nsc-table .column-header, .nsc-table .filler { -fx-background-color: " + Theme.PRIMARY_BLUE + "; "
                    + "-fx-size: 56px; -fx-border-color: #3F6DB5; -fx-border-width: 0 1 0 0; }",
            ".nsc-table .column-header .label { -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: CENTER; "
                    + "-fx-text-alignment: center; }",
            ".nsc-table .table-row-cell { -fx-background-color: white; -fx-border-color: #D9DEE7; -fx-border-width: 0 0 1 0; }",
            ".nsc-table .table-row-cell:filled:selected { -fx-background-color: #C9D2DD; }",
            ".nsc-table .table-row-cell:filled:selected .table-cell { -fx-text-fill: " + Theme.PRIMARY_TEXT + "; }",
            ".nsc-table .table-cell { -fx-text-fill: " + Theme.PRIMARY_TEXT + "; }");

    private static final String SECONDARY = "-fx-background-color: #DCE1EC; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; "
            + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-padding: 8 16 8 16; -fx-cursor: hand;";
    private static final String PRIMARY = "-fx-background-color: " + Theme.INFORMATION + "; -fx-text-fill: white; "
            + "-fx-font-size: 14px; -fx-background-radius: 6; -fx-padding: 8 16 8 16; -fx-cursor: hand;";
    private static final String FIELD = "-fx-background-color: #DCE1EC; -fx-background-radius: 6; -fx-font-size: 14px; "
            + "-fx-padding: 6 12 6 12;";

    /** A filtered list shown 20 rows at a time. */
    private static final class Paged<T> {
        final FilteredList<T> filtered;
        final ObservableList<T> page = FXCollections.observableArrayList();
        final Runnable onChange;
        int index;

        Paged(ObservableList<T> source, Runnable onChange) {
            this.filtered = new FilteredList<>(source, x -> true);
            this.onChange = onChange;
            filtered.addListener((javafx.collections.ListChangeListener<T>) c -> refresh());
        }

        void setPredicate(Predicate<T> p) {
            index = 0;
            filtered.setPredicate(p);
            refresh();
        }

        int pages() {
            return Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        }

        void goTo(int newIndex) {
            index = Math.max(0, Math.min(newIndex, pages() - 1));
            refresh();
        }

        /** Moves to the page that holds {@code item}, if it is shown at all. */
        void showItem(T item) {
            int i = filtered.indexOf(item);
            if (i >= 0) {
                index = i / PAGE_SIZE;
                refresh();
            }
        }

        void refresh() {
            if (index >= pages()) {
                index = pages() - 1;
            }
            int from = index * PAGE_SIZE;
            int to = Math.min(filtered.size(), from + PAGE_SIZE);
            page.setAll(new ArrayList<>(filtered.subList(from, to)));
            onChange.run();
        }
    }

    private final ScreenContext screen;
    private final InventoryService inventory;

    private final BorderPane view = new BorderPane();
    private final ObservableList<StockRow> rows = FXCollections.observableArrayList();
    private final ObservableList<StockMovement> movements = FXCollections.observableArrayList();
    private final Paged<StockRow> stockPaged = new Paged<>(rows, this::updateFooter);
    private final Paged<StockMovement> historyPaged = new Paged<>(movements, this::updateFooter);
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> userNames = new HashMap<>();

    private final TableView<StockRow> stockTable = new TableView<>(stockPaged.page);
    private final TableView<StockMovement> historyTable = new TableView<>(historyPaged.page);
    private final ToggleButton stockTab = new ToggleButton("Current Stock");
    private final ToggleButton historyTab = new ToggleButton("Stock Movement History");
    private final TextField search = new TextField();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final Label showing = new Label();
    private final HBox pager = new HBox(6);
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
            loadUserNames(history);
            rows.setAll(loaded);
            movements.setAll(history);
            updateCards();
            if (keepProductId != null) {
                for (StockRow r : rows) {
                    if (r.productId().equals(keepProductId)) {
                        stockPaged.showItem(r);
                        stockTable.getSelectionModel().select(r);
                        stockTable.scrollTo(r);
                        break;
                    }
                }
            }
        } catch (InventoryException e) {
            showError(e.getMessage());
        } catch (RuntimeException e) {
            showError(UiErrors.message(e, "Inventory could not be loaded. Try again."));
        }
    }

    /** "Performed By" shows the person's name; falls back to the user ID if the name can't be found. */
    private void loadUserNames(List<StockMovement> history) {
        history.stream().map(StockMovement::performedBy).filter(id -> id != null).distinct().forEach(id -> {
            if (!userNames.containsKey(id)) {
                String name = null;
                try {
                    name = screen.app().userName(id);
                } catch (RuntimeException ignored) {
                    // keep the ID
                }
                userNames.put(id, name == null ? id : name);
            }
        });
    }

    private void updateCards() {
        long low = rows.stream().filter(r -> r.status() == StockStatus.LOW_STOCK).count();
        long out = rows.stream().filter(r -> r.status() == StockStatus.OUT_OF_STOCK).count();
        totalValue.setText(String.valueOf(rows.size()));
        lowValue.setText(String.valueOf(low));
        outValue.setText(String.valueOf(out));
    }

    private void act(StockActionDialog.Mode mode, StockRow preselect) {
        String saved = StockActionDialog.show(screen.stage(), mode, new ArrayList<>(rows), preselect,
                (id, qty, reason, note) -> {
                    String session = screen.sessionId();
                    switch (mode) {
                        case STOCK_IN -> inventory.stockIn(session, id, qty, note);
                        case STOCK_OUT -> inventory.stockOut(session, id, qty, reason, note);
                        case ADJUST -> inventory.adjust(session, id, qty, reason, note);
                        case REORDER -> inventory.setReorderLevel(session, id, qty);
                    }
                });
        if (saved != null) {
            reload(saved);
            rows.stream().filter(r -> r.productId().equals(saved)).findFirst().ifPresent(r ->
                    showInfo(r.name() + ": " + Quantity.format(r.onHand()) + " " + r.unit() + " on hand."));
        }
    }

    private void viewRow(StockRow row) {
        Alert info = new Alert(Alert.AlertType.NONE);
        info.initOwner(screen.stage());
        info.setTitle("Product stock");
        info.setHeaderText(row.name());
        ButtonType reorder = new ButtonType("Change Reorder Level");
        info.getDialogPane().getButtonTypes().addAll(reorder, ButtonType.CLOSE);
        info.setContentText("Code: " + code(row)
                + "\nCurrent stock: " + Quantity.format(row.onHand()) + " " + row.unit()
                + "\nReorder level: " + (row.reorderLevel().signum() == 0 ? "Not set" : Quantity.format(row.reorderLevel()))
                + "\nStatus: " + row.status().label()
                + "\nLast updated: " + date(row.updatedAt()));
        info.showAndWait().filter(b -> b == reorder).ifPresent(b -> act(StockActionDialog.Mode.REORDER, row));
    }

    /* ------------------------------ layout -------------------------------- */

    private void buildView() {
        // --- tab bar ---
        ToggleGroup group = new ToggleGroup();
        stockTab.setToggleGroup(group);
        historyTab.setToggleGroup(group);
        stockTab.setSelected(true);
        group.selectedToggleProperty().addListener((o, was, is) -> {
            if (is == null) {
                was.setSelected(true);   // always keep one tab selected
                return;
            }
            boolean stock = stockTab.isSelected();
            stockTable.setVisible(stock);
            historyTable.setVisible(!stock);
            statusFilter.setDisable(!stock);
            styleTabs();
            updateFooter();
        });
        for (ToggleButton t : List.of(stockTab, historyTab)) {
            t.setMaxWidth(Double.MAX_VALUE);
            t.setFocusTraversable(false);
            HBox.setHgrow(t, Priority.ALWAYS);
        }
        styleTabs();
        Button home = new Button("Home  (Esc)");
        home.setStyle(SECONDARY);
        home.setFocusTraversable(false);
        home.setOnAction(e -> screen.goHome());
        HBox tabs = new HBox(8, stockTab, historyTab, home);
        tabs.setAlignment(Pos.CENTER);
        tabs.setPadding(new Insets(0));

        // --- toolbar ---
        search.setPromptText("Scan barcode or search product...");
        search.setStyle(FIELD);
        search.setPrefWidth(300);
        statusFilter.getItems().addAll(ALL, StockStatus.IN_STOCK.label(), StockStatus.LOW_STOCK.label(),
                StockStatus.OUT_OF_STOCK.label());
        statusFilter.setValue(ALL);
        statusFilter.setStyle(FIELD);
        ComboBox<String> category = new ComboBox<>();   // needs a category on ProductInfo (Product module)
        category.setPromptText("Category");
        category.setStyle(FIELD);
        category.setDisable(true);
        search.textProperty().addListener((o, was, is) -> applyFilters());
        statusFilter.valueProperty().addListener((o, was, is) -> applyFilters());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button stockIn = toolbarButton("+  Stock In", PRIMARY, StockActionDialog.Mode.STOCK_IN);
        Button stockOut = toolbarButton("-  Stock Out", SECONDARY, StockActionDialog.Mode.STOCK_OUT);
        Button adjust = toolbarButton("Adjust Stock", SECONDARY, StockActionDialog.Mode.ADJUST);
        HBox toolbar = new HBox(12, search, statusFilter, category, spacer, stockIn, stockOut, adjust);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // --- tables ---
        buildStockTable();
        buildHistoryTable();
        historyTable.setVisible(false);
        StackPane tables = new StackPane(stockTable, historyTable);
        VBox.setVgrow(tables, Priority.ALWAYS);

        // --- footer: showing / pager, then the three boxes ---
        showing.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        pager.setAlignment(Pos.CENTER_RIGHT);
        HBox pageRow = new HBox(showing, fill, pager);
        pageRow.setAlignment(Pos.CENTER_LEFT);

        HBox boxes = new HBox(16, statBox("Total Products:", totalValue), statBox("Low Stock:", lowValue),
                statBox("Out of Stock:", outValue));
        boxes.setAlignment(Pos.CENTER);

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(14, tabs, toolbar, tables, pageRow, boxes, message);
        content.setPadding(new Insets(20, 28, 16, 28));
        view.setCenter(content);
        view.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + "; -fx-font-family: '" + Theme.FONT_FAMILY + "';");
        String css = Base64.getEncoder().encodeToString(TABLE_CSS.getBytes(StandardCharsets.UTF_8));
        view.getStylesheets().add("data:text/css;base64," + css);

        view.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                screen.goHome();
                e.consume();
            }
        });
    }

    private void buildStockTable() {
        stockTable.getStyleClass().add("nsc-table");
        stockTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        stockTable.setFixedCellSize(40);
        stockTable.setPlaceholder(new Label("No products found."));

        TableColumn<StockRow, String> status = col("Status", 110, r -> r.status().label());
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                if (empty || text == null) {
                    setGraphic(null);
                    return;
                }
                Label chip = new Label(text);   // readable text, never colour alone
                chip.setStyle("-fx-background-color: " + chipColor(text) + "; -fx-text-fill: white; "
                        + "-fx-font-size: 12px; -fx-background-radius: 12; -fx-padding: 3 14 3 14;");
                setGraphic(chip);
                setAlignment(Pos.CENTER);
            }
        });

        TableColumn<StockRow, String> actions = col("Actions", 170, r -> "actions");
        actions.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                StockRow row = empty ? null : getTableRow() == null ? null : (StockRow) getTableRow().getItem();
                if (row == null) {
                    setGraphic(null);
                    return;
                }
                Button view = new Button("View");
                view.setStyle(SECONDARY + "-fx-font-size: 12px; -fx-padding: 4 14 4 14; -fx-background-radius: 12;");
                view.setOnAction(e -> viewRow(row));
                Button adj = new Button("Adjust");
                adj.setStyle(PRIMARY + "-fx-font-size: 12px; -fx-padding: 4 14 4 14; -fx-background-radius: 12;");
                adj.setOnAction(e -> act(StockActionDialog.Mode.ADJUST, row));
                HBox box = new HBox(8, view, adj);
                box.setAlignment(Pos.CENTER);
                setGraphic(box);
            }
        });

        stockTable.getColumns().add(col("Product Code", 130, StockRow::barcodeOrId));
        stockTable.getColumns().add(col("Product Name", 260, StockRow::name));
        stockTable.getColumns().add(col("Current Stock", 100, r -> Quantity.format(r.onHand())));
        stockTable.getColumns().add(col("Reorder Level", 100,
                r -> r.reorderLevel().signum() == 0 ? "Not set" : Quantity.format(r.reorderLevel())));
        stockTable.getColumns().add(status);
        stockTable.getColumns().add(col("Last Updated", 110, r -> date(r.updatedAt())));
        stockTable.getColumns().add(actions);
    }

    private void buildHistoryTable() {
        historyTable.getStyleClass().add("nsc-table");
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        historyTable.setFixedCellSize(40);
        historyTable.setPlaceholder(new Label("No stock movements yet."));

        TableColumn<StockMovement, String> qty = col("Quantity", 90,
                m -> (m.quantity().signum() > 0 ? "+" : "") + Quantity.format(m.quantity()));
        qty.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                if (!empty && text != null) {
                    setStyle("-fx-alignment: CENTER; -fx-text-fill: " + (text.startsWith("+") ? Theme.SUCCESS : Theme.ERROR) + ";");
                }
            }
        });

        historyTable.getColumns().add(col("Date / Time", 130, m -> DAY_TIME.format(PhTime.of(m.createdAt()))));
        historyTable.getColumns().add(col("Product", 220, m -> names.getOrDefault(m.productId(), m.productId())));
        historyTable.getColumns().add(col("Type", 100, m -> m.type().label()));
        historyTable.getColumns().add(qty);
        historyTable.getColumns().add(col("Before", 80, m -> Quantity.format(m.quantityBefore())));
        historyTable.getColumns().add(col("After", 80, m -> Quantity.format(m.quantityAfter())));
        historyTable.getColumns().add(col("Reference", 170, InventoryScreen::referenceText));
        historyTable.getColumns().add(col("Performed By", 130,
                m -> m.performedBy() == null ? "POS sale" : userNames.getOrDefault(m.performedBy(), m.performedBy())));
    }

    /* ------------------------------ helpers ------------------------------- */

    private void applyFilters() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String st = statusFilter.getValue();
        stockPaged.setPredicate(r ->
                (q.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(q)
                        || r.productId().toLowerCase(Locale.ROOT).contains(q)
                        || (r.barcode() != null && r.barcode().contains(q)))
                        && (st == null || ALL.equals(st) || st.equals(r.status().label())));
        historyPaged.setPredicate(m -> q.isEmpty()
                || names.getOrDefault(m.productId(), m.productId()).toLowerCase(Locale.ROOT).contains(q)
                || m.type().label().toLowerCase(Locale.ROOT).contains(q)
                || referenceText(m).toLowerCase(Locale.ROOT).contains(q)
                || (m.performedBy() != null
                        && userNames.getOrDefault(m.performedBy(), m.performedBy()).toLowerCase(Locale.ROOT).contains(q)));
    }

    private Paged<?> active() {
        return stockTab.isSelected() ? stockPaged : historyPaged;
    }

    /** "Showing 1-20 of 248" and the page links. */
    private void updateFooter() {
        Paged<?> p = active();
        int total = p.filtered.size();
        int from = total == 0 ? 0 : p.index * PAGE_SIZE + 1;
        int to = Math.min(total, (p.index + 1) * PAGE_SIZE);
        showing.setText("Showing " + from + "-" + to + " of " + total);

        pager.getChildren().clear();
        int pages = p.pages();
        pager.getChildren().add(pageLink("<", p.index - 1, false, p.index > 0));
        int first = Math.max(0, Math.min(p.index - 2, pages - 5));
        for (int i = first; i < Math.min(pages, first + 5); i++) {
            pager.getChildren().add(pageLink(String.valueOf(i + 1), i, i == p.index, true));
        }
        pager.getChildren().add(pageLink(">", p.index + 1, false, p.index < pages - 1));
    }

    private Button pageLink(String text, int target, boolean current, boolean enabled) {
        Button b = new Button(text);
        b.setFocusTraversable(false);
        b.setDisable(!enabled);
        b.setStyle("-fx-background-color: transparent; -fx-padding: 2 4 2 4; -fx-cursor: hand; -fx-font-size: 12px; "
                + "-fx-text-fill: " + (current ? Theme.PRIMARY_TEXT : Theme.PRIMARY_BLUE) + ";"
                + (current ? " -fx-font-weight: bold;" : ""));
        b.setOnAction(e -> active().goTo(target));
        return b;
    }

    private void styleTabs() {
        String base = "-fx-background-color: #DCE1EC; -fx-background-radius: 8; -fx-font-size: 14px; "
                + "-fx-padding: 12 16 12 16; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";";
        stockTab.setStyle(base + (stockTab.isSelected() ? " -fx-font-weight: bold;" : ""));
        historyTab.setStyle(base + (historyTab.isSelected() ? " -fx-font-weight: bold;" : ""));
    }

    private Button toolbarButton(String text, String style, StockActionDialog.Mode mode) {
        Button b = new Button(text);
        b.setStyle(style);
        b.setOnAction(e -> act(mode, stockTab.isSelected() ? stockTable.getSelectionModel().getSelectedItem() : null));
        return b;
    }

    private static HBox statBox(String name, Label value) {
        Label label = new Label(name);
        label.setStyle("-fx-font-size: 14px; -fx-text-fill: white;");
        value.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");
        HBox box = new HBox(10, label, value);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-padding: 10 28 10 28;");
        return box;
    }

    private static String code(StockRow r) {
        return r.barcodeOrId();
    }

    private static String date(Instant at) {
        return at == null ? "-" : DAY.format(PhTime.of(at));
    }

    /** Reference column: the invoice / PO number, then the reason, then the note (whatever exists). */
    private static String referenceText(StockMovement m) {
        return Stream.of(m.referenceNo(), m.reason() == null ? null : m.reason().label(), m.note())
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + " - " + b).orElse("");
    }

    private static String chipColor(String label) {
        if (label.equals(StockStatus.IN_STOCK.label())) {
            return Theme.SUCCESS;
        }
        if (label.equals(StockStatus.LOW_STOCK.label())) {
            return Theme.WARNING;
        }
        return Theme.ERROR;
    }

    private void showError(String text) {
        message.setText(text);
        message.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + Theme.ERROR + ";");
    }

    private void showInfo(String text) {
        message.setText(text);
        message.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SUCCESS + ";");
    }

    private static <T> TableColumn<T, String> col(String name, double width, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setReorderable(false);
        c.setStyle("-fx-alignment: CENTER;");
        return c;
    }
}
