package inventory.ui;

import app.ScreenContext;
import inventory.model.StockMovement;
import inventory.model.StockReason;
import inventory.model.StockRow;
import inventory.model.StockStatus;
import inventory.service.InventoryException;
import inventory.service.InventoryService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
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
import javafx.util.StringConverter;
import shared.Modal;
import shared.PhTime;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.TabBar;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.Toast;
import shared.UiErrors;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The Inventory screen, built from the same shared parts as the Products screen. Shown inside the
 * sidebar frame (app.AppShell), which owns Esc (toggles the sidebar).
 *
 * <p>Two tabs, each with its own toolbar:
 * <ul>
 *   <li><b>Current Stock</b>: search, Status, Category, Reset, and the Stock In / Stock Out / Adjust Stock
 *       buttons. Each row has + In / - Out / Adjust / View, and the Reorder Level cell has an Edit button.</li>
 *   <li><b>Stock Movement History</b> (read-only): search, From Date, To Date and Reset. No stock buttons.</li>
 * </ul>
 * Filters apply as soon as they change. Major results (stock updated) are shown as a toast, a lost
 * database connection as a pop-up; field problems stay inside the pop-up forms.
 *
 * <p>Only calls {@link InventoryService}; no SQL and no stock rules here.
 */
public final class InventoryScreen {

    private static final int HISTORY_LIMIT = 500;
    private static final String ALL_CATEGORIES = "All Categories";
    private static final String NO_CATEGORY = "(No category)";
    private static final String BAD_RANGE = "The From date is after the To date.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d/yy", Locale.US);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final DateTimeFormatter PICK_DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);

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
    private final Map<String, String> barcodes = new HashMap<>();
    private final Map<String, String> userNames = new HashMap<>();
    private Map<String, String> categoryOf = Map.of();   // product ID -> category name
    private Tab tab = Tab.STOCK;
    private boolean dbWarningShown;   // one "database unavailable" pop-up per outage, not one per click

    private final VBox view = new VBox(16);
    private final TabBar tabs = new TabBar(List.of("Current Stock", "Stock Movement History"),
            n -> showTab(Tab.values()[n]));

    // Current Stock toolbar
    private final TextField search = new TextField();
    private final SearchPicker<StatusChoice> statusFilter = new SearchPicker<>(false, false);
    private final SearchPicker<String> categoryFilter = new SearchPicker<>(false);   // with a search box
    private boolean updatingCategories;   // true while the screen refills the Category drop-down itself

    // Movement History toolbar
    private final TextField historySearch = new TextField();
    private final DatePicker fromDate = datePicker("From Date", () -> { });
    private final DatePicker toDate = datePicker("To Date", () -> { });

    private final StackPane toolbarSlot = new StackPane();
    private final TableView<StockRow> stockTable = Tables.create();
    private final TableView<StockMovement> historyTable = Tables.create();
    private final TablePager stockPager = TablePager.boxed(this::showStockPage);
    private final TablePager historyPager = TablePager.boxed(this::showHistoryPage);
    private final StackPane pagerSlot = new StackPane();
    private final Label totalValue = new Label("0");
    private final Label lowValue = new Label("0");
    private final Label outValue = new Label("0");
    private final Label message = new Label();

    private HBox stockToolbar;
    private HBox historyToolbar;

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
            barcodes.clear();
            loaded.forEach(r -> {
                names.put(r.productId(), r.name());
                if (r.hasBarcode()) {
                    barcodes.put(r.productId(), r.barcode());
                }
            });
            loadUserNames(history);
            loadCategories();
            rows = loaded;
            movements = history;
            dbWarningShown = false;
            updateCards();
            applyFilters(false);
            if (keepProductId != null) {
                selectProduct(keepProductId);
            }
        } catch (InventoryException e) {
            showError(e.getMessage());
        } catch (RuntimeException e) {
            String text = UiErrors.message(e, "Inventory could not be loaded. Try again.");
            showError(text);
            if (UiErrors.isConnectionProblem(e) && !dbWarningShown) {
                dbWarningShown = true;
                Modal.notice(screen.stage(), "Database unavailable", text);
            }
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
                (id, qty, reason, note, reorder) -> {
                    String session = screen.sessionId();
                    switch (mode) {
                        case STOCK_IN -> inventory.stockIn(session, id, qty, note);
                        case STOCK_OUT -> inventory.stockOut(session, id, qty, reason, note);
                        case ADJUST -> adjustStock(session, id, qty, reason, note, reorder);
                        case REORDER -> inventory.setReorderLevel(session, id, qty);
                    }
                });
        if (saved != null) {
            reload(saved);
            String title = mode == StockActionDialog.Mode.REORDER ? "Reorder level updated" : "Stock updated";
            rows.stream().filter(r -> r.productId().equals(saved)).findFirst().ifPresent(r ->
                    Toast.success(screen.stage(), title, r.name() + ": " + Quantity.format(r.onHand()) + " "
                            + r.unit() + " on hand, reorder level " + Quantity.format(r.reorderLevel()) + "."));
        }
    }

    /**
     * Adjust Stock can change the counted quantity, the reorder level, or both. Only what actually
     * changed is sent: an adjustment to the same quantity would be refused by the service, and a
     * reorder-level-only change needs no reason.
     */
    private void adjustStock(String session, String productId, BigDecimal qty, StockReason reason, String note,
                             BigDecimal reorder) throws InventoryException {
        StockRow row = rows.stream().filter(r -> r.productId().equals(productId)).findFirst().orElse(null);
        boolean stockChanged = qty != null && (row == null || qty.compareTo(row.onHand()) != 0);
        boolean reorderChanged = reorder != null && (row == null || reorder.compareTo(row.reorderLevel()) != 0);
        if (!stockChanged && !reorderChanged) {
            throw new InventoryException("Change the new quantity or the reorder level first.");
        }
        if (reorder != null && reorder.signum() < 0) {
            throw new InventoryException("Reorder level cannot be negative.");   // checked before anything is saved
        }
        if (stockChanged) {
            inventory.adjust(session, productId, qty, reason, note);
        }
        if (reorderChanged) {
            inventory.setReorderLevel(session, productId, reorder);
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
        // --- Current Stock toolbar (filters apply immediately; Reset restores the unfiltered list) ---
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

        Button stockReset = new Button("Reset");
        toolbarStyle(stockReset, Theme.BORDER, Theme.PRIMARY_TEXT);
        stockReset.setOnAction(e -> {
            search.clear();
            statusFilter.setValue(StatusChoice.ALL);
            categoryFilter.setValue(ALL_CATEGORIES);
            applyFilters(true);
        });

        Button stockIn = toolbarButton("+ Stock In", StockActionDialog.Mode.STOCK_IN);
        Styles.primaryButton(stockIn);
        Button stockOut = toolbarButton("- Stock Out", StockActionDialog.Mode.STOCK_OUT);
        toolbarStyle(stockOut, Theme.ACCENT_RED, "white");
        Button adjust = toolbarButton("Adjust Stock", StockActionDialog.Mode.ADJUST);
        toolbarStyle(adjust, Theme.BORDER, Theme.PRIMARY_TEXT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        stockToolbar = new HBox(12, search, statusFilter, categoryFilter, stockReset, spacer, stockIn, stockOut, adjust);
        stockToolbar.setAlignment(Pos.CENTER_LEFT);
        stockToolbar.setMaxWidth(Double.MAX_VALUE);

        // --- Movement History toolbar (read-only: no stock buttons) ---
        historySearch.setPromptText("Scan barcode or search product...");
        historySearch.setPrefWidth(300);
        Styles.input(historySearch);
        historySearch.textProperty().addListener((o, was, is) -> applyFilters(true));
        fromDate.valueProperty().addListener((o, was, is) -> applyFilters(true));
        toDate.valueProperty().addListener((o, was, is) -> applyFilters(true));

        Button reset = new Button("Reset");
        toolbarStyle(reset, Theme.BORDER, Theme.PRIMARY_TEXT);
        reset.setOnAction(e -> {
            historySearch.clear();
            fromDate.setValue(null);
            toDate.setValue(null);
            applyFilters(true);
        });
        historyToolbar = new HBox(12, historySearch, fromDate, toDate, reset);
        historyToolbar.setAlignment(Pos.CENTER_LEFT);
        historyToolbar.setMaxWidth(Double.MAX_VALUE);

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

        view.getChildren().addAll(tabs.getView(), toolbarSlot, tables, message, pagerSlot, boxes);
        // Esc is handled by the sidebar frame (app.AppShell): it toggles the sidebar.
    }

    private void buildStockTable() {
        stockTable.getColumns().add(Tables.<StockRow>numberColumn(stockPager));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Code", 130, 110, InventoryScreen::codeCell));
        stockTable.getColumns().add(Tables.<StockRow>textColumn("Product Name", 300, StockRow::name));
        stockTable.getColumns().add(Tables.<StockRow>textColumn("Current\nStock", 100,
                r -> Quantity.format(r.onHand()) + " " + r.unit()));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Reorder\nLevel", 130, 120, this::reorderCell));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Status", 120, 110, r -> statusPill(r.status())));
        stockTable.getColumns().add(Tables.<StockRow>textColumn("Last\nUpdated", 100, r -> date(r.updatedAt())));
        stockTable.getColumns().add(Tables.<StockRow>nodeColumn("Actions", 250, 240, this::actions));
        stockTable.setPlaceholder(new Label("No products found."));
        stockPager.fitToHeight(stockTable, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showStockPage);
    }

    private void buildHistoryTable() {
        historyTable.getColumns().add(Tables.<StockMovement>numberColumn(historyPager));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Date / Time", 130,
                m -> DAY_TIME.format(PhTime.of(m.createdAt()))));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Product Name", 220,
                m -> names.getOrDefault(m.productId(), m.productId())));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Type", 100, m -> m.type().label()));
        historyTable.getColumns().add(Tables.<StockMovement>nodeColumn("Quantity", 90, 80, this::quantityCell));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Before", 70,
                m -> Quantity.format(m.quantityBefore())));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("After", 70,
                m -> Quantity.format(m.quantityAfter())));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Reason / Reference", 200,
                InventoryScreen::referenceText));
        historyTable.getColumns().add(Tables.<StockMovement>textColumn("Performed By", 140,
                m -> m.performedBy() == null ? "POS sale" : userNames.getOrDefault(m.performedBy(), m.performedBy())));
        historyTable.setPlaceholder(new Label("No stock movements found."));
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
        HBox box = new HBox(6, in, out, adj, viewButton);
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
        toolbarSlot.getChildren().setAll(stock ? stockToolbar : historyToolbar);   // History has no stock buttons
        pagerSlot.getChildren().setAll(stock ? stockPager.getView() : historyPager.getView());
    }

    /**
     * Narrows both lists. Current Stock: search, status, category. Movement History: its own search
     * and the From / To dates (PH time). {@code toFirstPage} after a filter change.
     */
    private void applyFilters(boolean toFirstPage) {
        // ---- Current Stock ----
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

        // ---- Movement History ----
        String hq = historySearch.getText() == null ? "" : historySearch.getText().trim().toLowerCase(Locale.ROOT);
        LocalDate from = fromDate.getValue();
        LocalDate to = toDate.getValue();
        List<StockMovement> m = new ArrayList<>();
        if (from != null && to != null && from.isAfter(to)) {
            showError(BAD_RANGE);   // a field problem: stays next to the filters
        } else {
            if (BAD_RANGE.equals(message.getText())) {
                message.setText("");
            }
            Instant fromAt = from == null ? null : PhTime.startOf(from);
            Instant toAt = to == null ? null : PhTime.startOf(to.plusDays(1));   // the To day is included
            for (StockMovement mv : movements) {
                if (fromAt != null && mv.createdAt().isBefore(fromAt)) {
                    continue;
                }
                if (toAt != null && !mv.createdAt().isBefore(toAt)) {
                    continue;
                }
                if (hq.isEmpty() || matchesHistory(mv, hq)) {
                    m.add(mv);
                }
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

    /** History search: product name, ID or barcode, type, reason / reference, or who did it. */
    private boolean matchesHistory(StockMovement mv, String q) {
        String barcode = barcodes.get(mv.productId());
        return names.getOrDefault(mv.productId(), mv.productId()).toLowerCase(Locale.ROOT).contains(q)
                || mv.productId().toLowerCase(Locale.ROOT).contains(q)
                || (barcode != null && barcode.contains(q))
                || mv.type().label().toLowerCase(Locale.ROOT).contains(q)
                || referenceText(mv).toLowerCase(Locale.ROOT).contains(q)
                || (mv.performedBy() != null && userNames.getOrDefault(mv.performedBy(), mv.performedBy())
                        .toLowerCase(Locale.ROOT).contains(q));
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

    /**
     * A grey From / To date box, the same as the Audit Logs ones: a click opens the calendar (the
     * date can't be typed); Delete clears it. Enter runs {@code onEnter}.
     */
    private static DatePicker datePicker(String prompt, Runnable onEnter) {
        DatePicker picker = new DatePicker();
        picker.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                picker.setValue(picker.getConverter().fromString(picker.getEditor().getText()));
                onEnter.run();
            }
        });
        picker.setPromptText(prompt);
        picker.setPrefWidth(150);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : PICK_DATE.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return LocalDate.parse(text.trim(), PICK_DATE);
                } catch (DateTimeParseException e) {
                    return picker.getValue();   // keep the last good date
                }
            }
        });
        String box = "-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 4; -fx-pref-height: 40; "
                + "-fx-font-size: 14px;";
        picker.setStyle(box + " -fx-border-color: transparent; -fx-border-radius: 4;");
        picker.focusedProperty().addListener((obs, was, is) -> picker.setStyle(box + " -fx-border-radius: 4; "
                + "-fx-border-color: " + (is ? Theme.PRIMARY_BLUE : "transparent") + ";"));
        picker.skinProperty().addListener((obs, old, skin) -> picker.lookupAll(".arrow-button").forEach(n ->
                n.setStyle("-fx-background-color: transparent; -fx-cursor: hand;")));
        picker.getEditor().setStyle("-fx-background-color: transparent; -fx-font-size: 14px; -fx-text-fill: "
                + Theme.PRIMARY_TEXT + "; -fx-prompt-text-fill: " + Theme.SECONDARY_TEXT + ";");
        Styles.calendarPopup(picker);   // rounded calendar, like the drop-down panels
        Styles.pickOnly(picker);        // no typing: a click opens the calendar
        return picker;
    }

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

    /** 0 when no level is set: with 0, no low-stock threshold applies (see StockStatus). */
    private static String reorderText(StockRow r) {
        return Quantity.format(r.reorderLevel());
    }

    private static String date(Instant at) {
        return at == null ? "-" : DAY.format(PhTime.of(at));
    }

    /** Reason / Reference column: the invoice / PO number, then the reason, then the note (whatever exists). */
    private static String referenceText(StockMovement m) {
        return Stream.of(m.referenceNo(), m.reason() == null ? null : m.reason().label(), m.note())
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + " - " + b).orElse("");
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }
}
