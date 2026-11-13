package system.ui;

import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import shared.Modal;
import shared.PhTime;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;
import system.model.InventoryLogEntry;
import system.model.InventoryLogFilter;
import system.service.InventoryLogService;
import system.service.SystemException;

import java.io.File;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The "Inventory Logs" tab, laid out like the Sales Reports design: every stock change with who
 * made it, why, and the quantity before and after. Filters by date, type and keyword; a View
 * button on every row; Export CSV; summary tiles.
 *
 * Read-only: the rows are Inventory's stock movements, read through {@link InventoryLogService}.
 */
final class InventoryLogPane implements SystemPane {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    /** One choice in the Type drop-down; {@code code} null = every type. */
    private record TypeChoice(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final TypeChoice ALL_TYPES = new TypeChoice(null, "All Types");
    private static final String EMPTY = "No stock changes found for these filters.";

    private final ScreenContext screen;
    private final InventoryLogService logs;

    private final DatePicker from = SystemUi.datePicker("From Date", this::load);
    private final DatePicker to = SystemUi.datePicker("To Date", this::load);
    private final SearchPicker<TypeChoice> typeFilter = new SearchPicker<>(false, false);
    private final TextField search = SystemUi.searchField("Search product or reference...", this::load);
    private final TableView<InventoryLogEntry> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label totalValue = new Label("0");
    private final Label inValue = new Label("0");
    private final Label outValue = new Label("0");
    private final Label adjustValue = new Label("0");
    private final Label message = new Label();
    private final VBox view = new VBox(16);
    private final UserNames names;

    private List<InventoryLogEntry> rows = List.of();
    private boolean updating;


    InventoryLogPane(ScreenContext screen, InventoryLogService logs) {
        this.screen = screen;
        this.logs = logs;
        this.names = new UserNames(screen);

        table.getColumns().add(Tables.<InventoryLogEntry>textColumn("Date / Time", 120,
                e -> ROW_TIME.format(PhTime.of(e.createdAt()))));
        table.getColumns().add(Tables.<InventoryLogEntry>nodeColumn("Product", 230, 150,
                e -> SystemUi.wrappedCell(e.productLabel())));
        table.getColumns().add(Tables.<InventoryLogEntry>textColumn("Type", 100,
                e -> InventoryLogEntry.label(e.type())));
        table.getColumns().add(Tables.<InventoryLogEntry>nodeColumn("Change", 85, 75, InventoryLogPane::changeCell));
        table.getColumns().add(Tables.<InventoryLogEntry>textColumn("Before", 75, e -> qty(e.quantityBefore())));
        table.getColumns().add(Tables.<InventoryLogEntry>textColumn("After", 75, e -> qty(e.quantityAfter())));
        table.getColumns().add(Tables.<InventoryLogEntry>nodeColumn("Reason / Reference", 220, 140,
                e -> SystemUi.wrappedCell(reference(e))));
        table.getColumns().add(Tables.<InventoryLogEntry>textColumn("Performed By", 140, this::performer));
        table.getColumns().add(Tables.<InventoryLogEntry>nodeColumn("Actions", 90, 80,
                e -> SystemUi.viewButton(() -> showDetails(e))));
        SystemUi.placeholder(table, EMPTY);
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        typeFilter.getItems().setAll(ALL_TYPES,
                new TypeChoice(InventoryLogEntry.STOCK_IN, "Stock In"),
                new TypeChoice(InventoryLogEntry.STOCK_OUT, "Stock Out"),
                new TypeChoice(InventoryLogEntry.ADJUSTMENT, "Adjustment"),
                new TypeChoice(InventoryLogEntry.SALE, "Sale"));
        typeFilter.setValue(ALL_TYPES);
        typeFilter.setPrefWidth(170);
        search.setPrefWidth(280);

        Runnable onFilter = () -> {
            if (!updating) {
                load();
            }
        };
        from.valueProperty().addListener((o, was, is) -> onFilter.run());
        to.valueProperty().addListener((o, was, is) -> onFilter.run());
        typeFilter.valueProperty().addListener((o, was, is) -> onFilter.run());
        SystemUi.onTyping(search, onFilter);
        Button reset = SystemUi.resetButton(() -> {
            updating = true;
            from.setValue(null);
            to.setValue(null);
            typeFilter.setValue(ALL_TYPES);
            search.clear();
            updating = false;
            load();
        });
        Button export = SystemUi.exportButton(this::export);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, from, to, typeFilter, search, reset, spacer, export);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        view.getChildren().addAll(toolbar, table, message, SystemUi.footer(pager,
                SystemUi.tile("Stock Changes", totalValue), SystemUi.tile("Stock In", inValue),
                SystemUi.tile("Stock Out", outValue), SystemUi.tile("Adjustments", adjustValue)));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    @Override
    public Parent getView() {
        return view;
    }

    /** Called every time the tab is shown. */
    @Override
    public void refresh() {
        load();
    }

    @Override
    public void focusSearch() {
        search.requestFocus();
    }

    /* --------------------------------- data --------------------------------- */

    private void load() {
        InventoryLogFilter filter;
        try {
            TypeChoice type = typeFilter.getValue();
            filter = new InventoryLogFilter(from.getValue(), to.getValue(), type == null ? null : type.code(),
                    search.getText());
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        try {
            rows = logs.find(screen.sessionId(), filter);
            rows.stream().map(InventoryLogEntry::performedBy).distinct().forEach(names::remember);
            SystemUi.placeholder(table, EMPTY);
            if (rows.size() >= InventoryLogService.MAX_ROWS) {
                message.setText("Showing the newest " + SystemUi.count(InventoryLogService.MAX_ROWS)
                        + " entries. Choose a shorter date range to see older ones.");
                Styles.muted(message);
            } else {
                message.setText("");
            }
        } catch (SystemException e) {
            loadFailed(e.getMessage());
        } catch (RuntimeException e) {
            loadFailed(UiErrors.message(e, "The inventory log could not be loaded. Try again."));
        }
        totalValue.setText(SystemUi.count(rows.size()));
        inValue.setText(count(e -> InventoryLogEntry.STOCK_IN.equals(e.type())));
        outValue.setText(count(e -> InventoryLogEntry.STOCK_OUT.equals(e.type())
                || InventoryLogEntry.SALE.equals(e.type())));
        adjustValue.setText(count(e -> InventoryLogEntry.ADJUSTMENT.equals(e.type())));
        pager.reset();
        showPage();
    }

    private String count(Predicate<InventoryLogEntry> test) {
        return SystemUi.count(rows.stream().filter(test).count());
    }

    private void loadFailed(String text) {
        rows = List.of();
        SystemUi.placeholder(table, SystemUi.NOT_LOADED);
        showError(text);
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    /* -------------------------------- actions -------------------------------- */

    private void showDetails(InventoryLogEntry e) {
        SystemUi.showDetails(screen.stage(), "Stock Change Details", List.of(
                new String[] {"Date / Time", FULL_TIME.format(PhTime.of(e.createdAt()))},
                new String[] {"Product", e.productLabel()},
                new String[] {"Product ID", e.productId()},
                new String[] {"Type", InventoryLogEntry.label(e.type())},
                new String[] {"Change", signed(e.quantity())},
                new String[] {"Before", qty(e.quantityBefore())},
                new String[] {"After", qty(e.quantityAfter())},
                new String[] {"Reason", InventoryLogEntry.label(e.reason())},
                new String[] {"Reference No.", e.referenceNo()},
                new String[] {"Note", e.note()},
                new String[] {"Performed By", performer(e)}));
    }

    @Override
    public void export() {
        if (rows.isEmpty()) {
            Modal.notice(screen.stage(), "Nothing to export", "There are no stock changes for these filters.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export inventory log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV file (*.csv)", "*.csv"));
        chooser.setInitialFileName("inventory-log-" + FILE_DATE.format(PhTime.today()) + ".csv");
        File file = chooser.showSaveDialog(screen.stage());
        if (file == null) {
            return;   // cancelled
        }
        try {
            int written = logs.exportCsv(screen.sessionId(), rows, file.toPath(), names::nameOrNull);
            Modal.notice(screen.stage(), "Export complete",
                    SystemUi.count(written) + " stock changes were saved to " + file.getName() + ".");
        } catch (SystemException e) {
            showError(e.getMessage());
        }
    }

    /* -------------------------------- helpers -------------------------------- */

    /** The Change cell: green for stock added, red for stock taken away; the sign is always shown too. */
    private static Node changeCell(InventoryLogEntry e) {
        return SystemUi.colouredCell(signed(e.quantity()), e.quantity().signum() > 0 ? Theme.SUCCESS : Theme.ERROR);
    }

    private static String signed(BigDecimal quantity) {
        return (quantity.signum() > 0 ? "+" : "") + Quantity.format(quantity);
    }

    private static String qty(BigDecimal quantity) {
        return quantity == null ? "" : Quantity.format(quantity);
    }

    /** The reason, the reference number and the note: whichever exist. */
    private static String reference(InventoryLogEntry e) {
        return Stream.of(InventoryLogEntry.label(e.reason()), e.referenceNo(), e.note())
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + " – " + b).orElse("");
    }

    /** Who made the change; sales are recorded by POS without a user. */
    private String performer(InventoryLogEntry e) {
        return e.performedBy() == null ? "POS sale" : names.name(e.performedBy());
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }
}
