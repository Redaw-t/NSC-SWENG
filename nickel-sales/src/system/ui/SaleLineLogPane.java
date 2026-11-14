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
import shared.Money;
import shared.PhTime;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;
import shared.Toast;

import system.model.SaleLineLogEntry;
import system.model.SaleLineLogFilter;
import system.service.SaleLineLogService;
import system.service.SystemException;

import java.io.File;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The "Open Items &amp; Price Edits" tab, laid out like the other log tabs: every line of a completed
 * sale that was an open item (F2, not in the catalog) or had its price edited at the till (F4), with
 * the invoice, the cashier, and the price before and after. Filters by date, type and keyword; a View
 * button on every row; Export CSV; summary tiles.
 *
 * Voids of those lines (POS Void Item / Void Transaction) are listed too, as their own rows: type
 * "... (Voided)", the return number, minus quantity and amount, when and by whom. Type "Voided" shows only them.
 *
 * Read-only: the rows are POS's saved sale and return lines, read through {@link SaleLineLogService}. Price edits
 * appear for sales saved since SaleLine.OriginalPrice was added; earlier edits are in Activity Logs
 * (PRICE_EDITED).
 */
final class SaleLineLogPane implements SystemPane {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    /** One choice in the Type drop-down; {@code code} null = both. */
    private record TypeChoice(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final TypeChoice ALL_TYPES = new TypeChoice(null, "All Types");
    private static final String EMPTY = "No open items or price edits found for these filters.";

    private final ScreenContext screen;
    private final SaleLineLogService logs;

    private final DatePicker from = SystemUi.datePicker("From Date", this::load);
    private final DatePicker to = SystemUi.datePicker("To Date", this::load);
    private final SearchPicker<TypeChoice> typeFilter = new SearchPicker<>(false, false);
    private final TextField search = SystemUi.searchField("Search item or invoice no...", this::load);
    private final TableView<SaleLineLogEntry> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label totalValue = new Label("0");
    private final Label openValue = new Label("0");
    private final Label editedValue = new Label("0");
    private final Label voidedValue = new Label("0");
    private final Label amountValue = new Label("₱0.00");
    private final Label message = new Label();
    private final VBox view = new VBox(16);
    private final UserNames names;

    private List<SaleLineLogEntry> rows = List.of();
    private boolean updating;


    SaleLineLogPane(ScreenContext screen, SaleLineLogService logs) {
        this.screen = screen;
        this.logs = logs;
        this.names = new UserNames(screen);

        table.getColumns().add(Tables.<SaleLineLogEntry>textColumn("Date / Time", 120,
                e -> ROW_TIME.format(PhTime.of(e.completedAt()))));
        // a void shows its return number (the invoice number + -01 ...)
        table.getColumns().add(Tables.<SaleLineLogEntry>textColumn("Invoice / Return No.", 170,
                SaleLineLogEntry::documentNo));
        table.getColumns().add(Tables.<SaleLineLogEntry>textColumn("Type", 130, SaleLineLogEntry::typeLabel));
        table.getColumns().add(Tables.<SaleLineLogEntry>nodeColumn("Product Name", 220, 140,
                e -> SystemUi.wrappedCell(e.productName())));
        table.getColumns().add(Tables.<SaleLineLogEntry>textColumn("Qty", 80,
                e -> Quantity.format(e.quantity()) + " " + e.unit()));
        table.getColumns().add(Tables.<SaleLineLogEntry>textColumn("Original Price", 110,
                e -> e.originalPrice() == null ? "" : Money.format(e.originalPrice())));
        table.getColumns().add(Tables.<SaleLineLogEntry>nodeColumn("Price Charged", 110, 95,
                SaleLineLogPane::priceCell));
        table.getColumns().add(Tables.<SaleLineLogEntry>textColumn("Amount", 100, e -> Money.format(e.lineTotal())));
        table.getColumns().add(Tables.<SaleLineLogEntry>textColumn("Cashier", 140, e -> names.name(e.cashierUserId())));
        table.getColumns().add(Tables.<SaleLineLogEntry>nodeColumn("Actions", 90, 80,
                e -> SystemUi.viewButton(() -> showDetails(e))));
        SystemUi.placeholder(table, EMPTY);
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        typeFilter.getItems().setAll(ALL_TYPES,
                new TypeChoice(SaleLineLogEntry.OPEN_ITEM, "Open Item"),
                new TypeChoice(SaleLineLogEntry.PRICE_EDIT, "Price Edit"),
                new TypeChoice(SaleLineLogEntry.VOIDED, "Voided"));
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
                SystemUi.tile("Lines", totalValue), SystemUi.tile("Open Items", openValue),
                SystemUi.tile("Price Edits", editedValue), SystemUi.tile("Voided", voidedValue),
                SystemUi.tile("Amount", amountValue)));
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
        SaleLineLogFilter filter;
        try {
            TypeChoice type = typeFilter.getValue();
            filter = new SaleLineLogFilter(from.getValue(), to.getValue(), type == null ? null : type.code(),
                    search.getText());
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        try {
            rows = logs.find(screen.sessionId(), filter);
            rows.stream().map(SaleLineLogEntry::cashierUserId).distinct().forEach(names::remember);
            SystemUi.placeholder(table, EMPTY);
            if (rows.size() >= SaleLineLogService.MAX_ROWS) {
                message.setText("Showing the newest " + SystemUi.count(SaleLineLogService.MAX_ROWS)
                        + " lines. Choose a shorter date range to see older ones.");
                Styles.muted(message);
            } else {
                message.setText("");
            }
        } catch (SystemException e) {
            loadFailed(e.getMessage());
        } catch (RuntimeException e) {
            loadFailed(UiErrors.message(e, "The open item and price edit history could not be loaded. Try again."));
        }
        totalValue.setText(SystemUi.count(rows.size()));
        // Open Items / Price Edits count the lines sold; voids are counted on their own (Amount is net of them)
        openValue.setText(count(e -> e.openItem() && !e.voided()));
        editedValue.setText(count(e -> e.priceEdited() && !e.voided()));
        voidedValue.setText(count(SaleLineLogEntry::voided));
        amountValue.setText("₱" + Money.format(rows.stream().map(SaleLineLogEntry::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        pager.reset();
        showPage();
    }

    private String count(Predicate<SaleLineLogEntry> test) {
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

    private void showDetails(SaleLineLogEntry e) {
        BigDecimal change = e.priceChange();
        SystemUi.showDetails(screen.stage(), e.voided() ? "Voided Line Details" : "Sale Line Details", List.of(
                new String[] {e.voided() ? "Voided On" : "Date / Time", FULL_TIME.format(PhTime.of(e.completedAt()))},
                new String[] {"Return No.", e.returnNo()},   // null rows are left out
                new String[] {"Invoice No.", e.invoiceNo()},
                new String[] {"Line No.", String.valueOf(e.lineNo())},
                new String[] {"Type", e.typeLabel()},
                new String[] {"Product Name", e.productName()},
                new String[] {"Product ID", e.openItem() ? e.productId() + "  (not in the product list)" : e.productId()},
                new String[] {"Quantity", Quantity.format(e.quantity()) + " " + e.unit()},
                new String[] {"Original Price", e.originalPrice() == null ? null : Money.format(e.originalPrice())},
                new String[] {"Price Charged", Money.format(e.unitPrice())},
                new String[] {"Price Change", change == null ? null : signed(change)},
                new String[] {"Amount", Money.format(e.lineTotal())},
                new String[] {"VAT", e.vatable() ? "VATable" : "VAT-exempt"},
                new String[] {e.voided() ? "Voided By" : "Cashier", names.name(e.cashierUserId())}));
    }

    @Override
    public void export() {
        if (rows.isEmpty()) {
            Modal.notice(screen.stage(), "Nothing to export", "There are no open items or price edits for these filters.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export open items and price edits");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV file (*.csv)", "*.csv"));
        chooser.setInitialFileName("open-items-price-edits-" + FILE_DATE.format(PhTime.today()) + ".csv");
        File file = chooser.showSaveDialog(screen.stage());
        if (file == null) {
            return;   // cancelled
        }
        try {
            int written = logs.exportCsv(screen.sessionId(), rows, file.toPath(), names::nameOrNull);
            Toast.success(screen.stage(), "Export complete",
                    SystemUi.count(written) + " rows were saved to " + file.getName() + ".");
        } catch (SystemException e) {
            showError(e.getMessage());
            Toast.error(screen.stage(), "Export failed", e.getMessage());
        }
    }

    /* -------------------------------- helpers -------------------------------- */

    /** Price Charged: red when an edit lowered the price, green when it raised it, plain otherwise. */
    private static Node priceCell(SaleLineLogEntry e) {
        BigDecimal change = e.priceChange();
        String text = Money.format(e.unitPrice());
        if (change == null || change.signum() == 0) {
            return SystemUi.colouredCell(text, Theme.PRIMARY_TEXT);
        }
        return SystemUi.colouredCell(text, change.signum() < 0 ? Theme.ERROR : Theme.SUCCESS);
    }

    private static String signed(BigDecimal amount) {
        return (amount.signum() > 0 ? "+" : amount.signum() < 0 ? "-" : "") + Money.format(amount.abs());
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }
}
