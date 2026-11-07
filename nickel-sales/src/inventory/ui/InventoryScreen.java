package inventory.ui;

import app.ScreenContext;
import inventory.model.StockMovement;
import inventory.model.StockRow;
import inventory.model.StockStatus;
import inventory.service.InventoryException;
import inventory.service.InventoryService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import shared.Modal;
import shared.PhTime;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.TabBar;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The Inventory screen, built from the same shared parts as the Products screen
 * ({@link TabBar}, {@link Tables}, {@link TablePager}, {@link SearchPicker}, {@link Styles}, {@link Modal})
 * so the two look alike. Shown inside the sidebar frame (app.AppShell); Esc goes home.
 *
 * <p>Two tabs: Current Stock and Stock Movement History. One toolbar (search, status, category,
 * Stock In / Stock Out / Adjust Stock), the table with a pager, and the Total / Low / Out of stock
 * boxes at the bottom. Each stock row has + In / - Out / Adjust / View, and the Reorder Level cell
 * has a small Edit button.
 *
 * <p>Only calls {@link InventoryService}; no SQL and no stock rules here.
 */
public final class InventoryScreen {

    private static final int HISTORY_LIMIT = 500;
    private static final String ALL_CATEGORIES = "All Categories";
    private static final String NO_CATEGORY = "(No category)";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d/yy", Locale.US);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);

    private enum Tab { STOCK, HISTORY }

    /** The Status filter's choices ("All Statuses" = no filter). */
    private enum StatusChoice {
        ALL("All Statuses", null),
        IN(StockStatus.IN_STOCK.label(), StockStatus.IN_STOCK),
        LOW(StockStatus.LOW_STOCK.label(), StockStatus.LOW_STOCK),
        OUT(StockStatus.OUT_OF_STOCK.label(), StockStatus.OUT_OF_STOCK);

        private final String label;
        private final StockStatus status;

        StatusChoice(String label, StockStatus status) {
            this.label = label;
            this.status = status;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ScreenContext screen;
    private final InventoryService inventory;

    private List<StockRow> rows = List.of();
    private List<StockMovement> movements = List.of();
    private List<StockRow> shownRows = List.of();
    private List<StockMovement> shownMovements = List.of();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> userNames = new HashMap<>();
    private Map<String, String> categoryOf = Map.of();   // product ID -> category name
    private Tab tab = Tab.STOCK;

    private final VBox view = new VBox(16);
    private final TabBar tabs = new TabBar(List.of("Current Stock", "Stock Movement History"),
            n -> showTab(Tab.values()[n]));
    private final TextField search = new TextField();
    private final SearchPicker<StatusChoice> statusFilter = new SearchPicker<>(false, false);
    private final SearchPicker<String> categoryFilter = new SearchPicker<>(false);   // with a search box
    private boolean updatingCategories;   // true while the screen refills the Category drop-down itself
    private final TableView<StockRow> stockTable = Tables.create();
    private final TableView<StockMovement> historyTable = Tables.create();
    private final TablePager stockPager = TablePager.boxed(this::showStockPage);
    private final TablePager historyPager = TablePager.boxed(this::showHistoryPage);
    private final StackPane pagerSlot = new StackPane();
    private final Label totalValue = new Label("0");
    private final Label lowValue = new Label("0");
    private final Label outValue = new Label("0");
    private final Label message = new Label();

    public InventoryScreen(ScreenContext screen, InventoryService inventory) {
        this.screen = screen;
        this.inventory = inventory;
        buildView();
        showTab(Tab.STOCK);
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
            loadCategories();
            rows = loaded;
            movements = history;
            updateCards();
            applyFilters(false);
            if (keepProductId != null) {
                selectProduct(keepProductId);
            }
        } catch (InventoryException e) {
            showError(e.getMessage());
        } catch (RuntimeException e) {
            showError(UiErrors.message(e, "Inventory could not be loaded. Try again."));
        }
    }

    /** Fills the Category drop-down ("All Categories" first) and keeps what was picked if it still exists. */
    private void loadCategories() throws InventoryException {
        categoryOf = inventory.productCategories(screen.sessionId());
        List<String> choices = new ArrayList<>();
        choices.add(ALL_CATEGORIES);
        choices.addAll(inventory.categoryNames(screen.sessionId()));
        choices.add(NO_CATEGORY);
        String keep = categoryFilter.getValue();
        updatingCategories = true;
        try {
            categoryFilter.getItems().setAll(choices);
            categoryFilter.setValue(keep != null && choices.contains(keep) ? keep : ALL_CATEGORIES);
        } finally {
            updatingCategories = false;
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

    /** Selects the product's row after a change (stays on the current page if it is shown there). */
    private void selectProduct(String productId) {
        stockTable.getItems().stream().filter(r -> r.productId().equals(productId)).findFirst()
                .ifPresent(r -> stockTable.getSelectionModel().select(r));
    }

    /** Opened from a table row: the product is fixed (no drop-down). */
    private void act(StockActionDialog.Mode mode, StockRow row) {
        act(mode, row, true);
    }

    /**
     * @param lockProduct true from a row button (product fixed); false from the toolbar
     *                    (product chosen in a searchable drop-down, {@code preselect} pre-filled)
     */
    private void act(StockActionDialog.Mode mode, StockRow preselect, boolean lockProduct) {
        String saved = StockActionDialog.show(screen.stage(), mode, new ArrayList<>(rows), preselect, lockProduct,
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

    /** The "View" pop-up: one product's stock, read only (same Modal design as Product Details). */
    private void viewRow(StockRow row) {
        Modal modal = new Modal(screen.stage(), 640);

        GridPane grid = Modal.grid();
        int r = 0;
        r = Modal.row(grid, r, "Product Name", modalValue(row.name()));
        r = Modal.row(grid, r, "Product Code", row.hasBarcode() ? modalValue(row.barcode()) : noBarcode(modalValue("No Barcode")));
        r = Modal.row(grid, r, "Current Stock", modalValue(Quantity.format(row.onHand()) + " " + row.unit()));
        r = Modal.row(grid, r, "Reorder Level", modalValue(reorderText(row)));
        r = Modal.row(grid, r, "Status", statusPill(row.status()));
        Modal.row(grid, r, "Last Updated", modalValue(date(row.updatedAt())));

        boolean[] changeReorder = {false};
        Button reorder = new Button("Change Reorder Level");
        Styles.modalButton(reorder, Theme.BORDER, Theme.PRIMARY_TEXT);
        reorder.setOnAction(e -> {
            changeReorder[0] = true;
            modal.close();
        });
        Button close = new Button("Close");
        Styles.modalButton(close, Theme.INFORMATION, "white");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title("Product Stock"), grid, Modal.buttons(reorder, close));
        modal.showAndWait();
        if (changeReorder[0]) {
            act(StockActionDialog.Mode.REORDER, row);
        }
    }

    /* ------------------------------ layout -------------------------------- */

    private void buildView() {
        // --- toolbar: same sizes as the Products toolbar ---
        search.setPromptText("Scan barcode or search product...");
        search.setPrefWidth(300);
        Styles.input(search);
        search.textProperty().addListener((o, was, is) -> applyFilters(true));

        statusFilter.getItems().setAll(StatusChoice.values());
        statusFilter.setValue(StatusChoice.ALL);
        statusFilter.setPrefWidth(160);
        statusFilter.valueProperty().addListener((o, was, is) -> applyFilters(true));

        // Category: Product.CategoryID -> Category.CategoryName, loaded by InventoryService (see loadCategories).
        categoryFilter.setPromptText(ALL_CATEGORIES);
        categoryFilter.setValue(ALL_CATEGORIES);
        categoryFilter.setPrefWidth(180);
        categoryFilter.valueProperty().addListener((o, was, is) -> {
            if (!updatingCategories) {
                applyFilters(true);
            }
        });

        Button stockIn = toolbarButton("+ Stock In", StockActionDialog.Mode.STOCK_IN);
        Styles.primaryButton(stockIn);
        Button stockOut = toolbarButton("- Stock Out", StockActionDialog.Mode.STOCK_OUT);
        toolbarStyle(stockOut, Theme.ACCENT_RED, "white");
        Button adjust = toolbarButton("Adjust Stock", StockActionDialog.Mode.ADJUST);
        toolbarStyle(adjust, Theme.BORDER, Theme.PRIMARY_TEXT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, search, statusFilter, categoryFilter, spacer, stockIn, stockOut, adjust);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // --- tables ---
        buildStockTable();
        buildHistoryTable();
        StackPane tables = new StackPane(stockTable, historyTable);
        VBox.setVgrow(tables, Priority.ALWAYS);

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        HBox boxes = new HBox(16, statBox("Total Products:", totalValue), statBox("Low Stock:", lowValue),
                statBox("Out of Stock:", outValue));
        boxes.setAlignment(Pos.CENTER);

        view.getChildren().addAll(tabs.getView(), toolbar, tables, message, pagerSlot, boxes);
        view.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                screen.goHome();
                e.consume();
            }
        });
    }

    private void buildStockTable() {
        stockTable.getColumns().add(Tables.<StockRow>numberColumn(stockPager));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Code", 120, 100, InventoryScreen::codeCell));
        stockTable.getColumns().add(Tables.<StockRow>textColumn("Description", 220, StockRow::name));
        stockTable.getColumns().add(Tables.<StockRow>textColumn("Current\nStock", 90,
                r -> Quantity.format(r.onHand()) + " " + r.unit()));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Reorder\nLevel", 130, 120, this::reorderCell));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Status", 110, 110, r -> statusPill(r.status())));
        stockTable.getColumns().add(Tables.<StockRow>textColumn("Last\nUpdated", 90, r -> date(r.updatedAt())));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Actions", 290, 270, this::actions));
        stockTable.setPlaceholder(new Label("No products found."));
        stockPager.fitToHeight(stockTable, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showStockPage);
    }

    private void buildHistoryTable() {
        historyTable.getColumns().add(Tables.<StockMovement>numberColumn(historyPager));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Date / Time", 130,
                m -> DAY_TIME.format(PhTime.of(m.createdAt()))));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Product", 200,
                m -> names.getOrDefault(m.productId(), m.productId())));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Type", 100, m -> m.type().label()));
        historyTable.getColumns().add(Tables.<StockMovement>nodeColumn("Quantity", 90, 80, this::quantityCell));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Before", 70,
                m -> Quantity.format(m.quantityBefore())));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("After", 70,
                m -> Quantity.format(m.quantityAfter())));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Reference", 170, InventoryScreen::referenceText));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Performed By", 130,
                m -> m.performedBy() == null ? "POS sale" : userNames.getOrDefault(m.performedBy(), m.performedBy())));
        historyTable.setPlaceholder(new Label("No stock movements yet."));
        historyPager.fitToHeight(historyTable, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showHistoryPage);
    }

    /* ------------------------------ table cells ------------------------------ */

    /** Reorder level centred, with a small Edit button pinned to the right. */
    private Node reorderCell(StockRow row) {
        Label value = new Label(reorderText(row));
        value.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Button edit = new Button("Edit");
        Styles.rowButton(edit, Theme.BORDER, Theme.PRIMARY_TEXT);
        edit.setStyle(edit.getStyle() + " -fx-font-size: 11px; -fx-padding: 2 8 2 8;");
        edit.setFocusTraversable(false);
        edit.setAccessibleText("Change the reorder level of " + row.name());
        edit.setOnAction(e -> act(StockActionDialog.Mode.REORDER, row));
        StackPane box = new StackPane(value, edit);
        StackPane.setAlignment(value, Pos.CENTER);
        StackPane.setAlignment(edit, Pos.CENTER_RIGHT);
        box.setPadding(new Insets(0, 4, 0, 4));
        return box;
    }

    /** + In (blue), - Out (red), Adjust, View: the same row buttons as the Products table. */
    private Node actions(StockRow row) {
        Button in = rowButton("+ In", Theme.INFORMATION, "white", "Stock in " + row.name());
        in.setOnAction(e -> act(StockActionDialog.Mode.STOCK_IN, row));
        Button out = rowButton("- Out", Theme.ACCENT_RED, "white", "Stock out " + row.name());
        out.setOnAction(e -> act(StockActionDialog.Mode.STOCK_OUT, row));
        Button adj = rowButton("Adjust", Theme.BORDER, Theme.PRIMARY_TEXT, "Adjust stock of " + row.name());
        adj.setOnAction(e -> act(StockActionDialog.Mode.ADJUST, row));
        Button viewButton = rowButton("View", Theme.BORDER, Theme.PRIMARY_TEXT, "View " + row.name());
        viewButton.setOnAction(e -> viewRow(row));
        HBox box = new HBox(8, in, out, adj, viewButton);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** "+6" in green, "-2" in red. */
    private Node quantityCell(StockMovement m) {
        boolean in = m.quantity().signum() > 0;
        Label label = new Label((in ? "+" : "") + Quantity.format(m.quantity()));
        label.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: "
                + (in ? Theme.SUCCESS : Theme.ERROR) + ";");
        return label;
    }

    private static Label statusPill(StockStatus status) {
        Label pill = new Label(status.label());   // readable text, never colour alone
        Styles.pill(pill, switch (status) {
            case IN_STOCK -> Theme.SUCCESS;
            case LOW_STOCK -> Theme.WARNING;
            case OUT_OF_STOCK -> Theme.ERROR;
        }, "white");
        return pill;
    }

    /* ------------------------------ tabs, filters, paging ------------------------------ */

    private void showTab(Tab next) {
        tab = next;
        tabs.select(next.ordinal());
        boolean stock = next == Tab.STOCK;
        stockTable.setVisible(stock);
        historyTable.setVisible(!stock);
        statusFilter.setDisable(!stock);
        pagerSlot.getChildren().setAll(stock ? stockPager.getView() : historyPager.getView());
    }

    /** Narrows both lists by the search text (and the stock list by status); {@code toFirstPage} after a filter change. */
    private void applyFilters(boolean toFirstPage) {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        StatusChoice st = statusFilter.getValue();
        String cat = categoryFilter.getValue();
        List<StockRow> r = new ArrayList<>();
        for (StockRow row : rows) {
            boolean text = q.isEmpty() || row.name().toLowerCase(Locale.ROOT).contains(q)
                    || row.productId().toLowerCase(Locale.ROOT).contains(q)
                    || (row.barcode() != null && row.barcode().contains(q));
            boolean status = st == null || st.status == null || st.status == row.status();
            if (text && status && inCategory(row.productId(), cat)) {
                r.add(row);
            }
        }
        shownRows = r;

        List<StockMovement> m = new ArrayList<>();
        for (StockMovement mv : movements) {
            if (!inCategory(mv.productId(), cat)) {
                continue;
            }
            if (q.isEmpty()
                    || names.getOrDefault(mv.productId(), mv.productId()).toLowerCase(Locale.ROOT).contains(q)
                    || mv.type().label().toLowerCase(Locale.ROOT).contains(q)
                    || referenceText(mv).toLowerCase(Locale.ROOT).contains(q)
                    || (mv.performedBy() != null && userNames.getOrDefault(mv.performedBy(), mv.performedBy())
                            .toLowerCase(Locale.ROOT).contains(q))) {
                m.add(mv);
            }
        }
        shownMovements = m;

        if (toFirstPage) {
            stockPager.reset();
            historyPager.reset();
        }
        showStockPage();
        showHistoryPage();
    }

    /** True if the product matches the Category filter ("All Categories" / null = everything). */
    private boolean inCategory(String productId, String category) {
        if (category == null || ALL_CATEGORIES.equals(category)) {
            return true;
        }
        String own = categoryOf.get(productId);
        return NO_CATEGORY.equals(category) ? own == null : category.equals(own);
    }

    private void showStockPage() {
        stockPager.setTotal(shownRows.size());
        stockTable.getItems().setAll(stockPager.slice(shownRows));
    }

    private void showHistoryPage() {
        historyPager.setTotal(shownMovements.size());
        historyTable.getItems().setAll(historyPager.slice(shownMovements));
    }

    /* ------------------------------ helpers ------------------------------- */

    /** Toolbar: always the product drop-down (with search); the selected table row is only pre-filled. */
    private Button toolbarButton(String text, StockActionDialog.Mode mode) {
        Button b = new Button(text);
        b.setOnAction(e -> act(mode, tab == Tab.STOCK ? stockTable.getSelectionModel().getSelectedItem() : null, false));
        return b;
    }

    /** Same size and shape as Styles.primaryButton ("+ Add Product"), in another colour. */
    private static void toolbarStyle(Button button, String background, String textColour) {
        String base = "-fx-background-radius: 4; -fx-text-fill: " + textColour + "; -fx-font-size: 15px; "
                + "-fx-font-weight: bold; -fx-padding: 0 22 0 22; -fx-pref-height: 40; -fx-cursor: hand; "
                + "-fx-background-color: ";
        String normal = base + background + ";";
        String hover = base + Styles.darker(background) + ";";
        button.setStyle(normal);
        button.hoverProperty().addListener((o, was, is) -> button.setStyle(is ? hover : normal));
    }

    private static Button rowButton(String text, String background, String textColour, String accessibleText) {
        Button b = new Button(text);
        Styles.rowButton(b, background, textColour);
        b.setFocusTraversable(false);
        b.setAccessibleText(accessibleText);
        return b;
    }

    private static HBox statBox(String name, Label value) {
        Label label = new Label(name);
        label.setStyle("-fx-font-size: 15px; -fx-text-fill: white;");
        value.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: white;");
        HBox box = new HBox(10, label, value);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-background-radius: 4; "
                + "-fx-padding: 10 28 10 28;");
        return box;
    }

    /** The Code cell, as on the Products page: the barcode, or a faint "No Barcode" when the product has none. */
    private static Node codeCell(StockRow row) {
        if (!row.hasBarcode()) {
            Label none = new Label("No Barcode");
            Styles.muted(none);
            return noBarcode(none);
        }
        Label code = new Label(row.barcode());
        code.setStyle("-fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return code;
    }

    /** Makes "No Barcode" faint, like the Products page. */
    private static Label noBarcode(Label label) {
        label.setOpacity(0.5);
        return label;
    }

    private static Label modalValue(String text) {
        Label label = new Label(text);
        Styles.modalValue(label);
        label.setWrapText(true);
        return label;
    }

    private static String reorderText(StockRow r) {
        return r.reorderLevel().signum() == 0 ? "Not set" : Quantity.format(r.reorderLevel());
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

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }

    private void showInfo(String text) {
        message.setText(text);
        message.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SUCCESS + ";");
    }
}
