package reports.ui;

import app.ScreenContext;
import inventory.contract.StockSnapshot;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import reports.model.InventoryReport;
import reports.service.InventoryReportService;
import reports.service.ReportException;
import reports.ui.ReportsUi.Choice;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;

import java.util.List;
import java.util.Locale;

/**
 * The "Inventory" tab: every product's current stock, or only the low-stock or out-of-stock
 * ones, with a search box. The status chip always has its words, not colour alone. Read-only.
 */
final class InventoryPane implements ReportsPane {

    private enum Show { ALL, LOW, OUT }

    private static final Choice<Show> ALL = new Choice<>(Show.ALL, "All Products");
    private static final Choice<Show> LOW = new Choice<>(Show.LOW, "Low Stock");
    private static final Choice<Show> OUT = new Choice<>(Show.OUT, "Out of Stock");

    private final ScreenContext screen;
    private final InventoryReportService service;

    private final SearchPicker<Choice<Show>> show = new SearchPicker<>(false, false);
    private final TextField search = ReportsUi.searchField("Search product or code...", this::filter);
    private final TableView<StockSnapshot> table = Tables.create();
    private final TablePager pager = TablePager.links(this::showPage);
    private final Label totalValue = new Label("0");
    private final Label lowValue = new Label("0");
    private final Label outValue = new Label("0");
    private final Label message = ReportsUi.messageLabel();
    private final VBox view = new VBox(16);

    private InventoryReport report = new InventoryReport(List.of(), List.of(), List.of());
    private List<StockSnapshot> rows = List.of();

    InventoryPane(ScreenContext screen, InventoryReportService service) {
        this.screen = screen;
        this.service = service;

        table.getColumns().add(Tables.numberColumn(pager));
        table.getColumns().add(Tables.<StockSnapshot>textColumn("Product Code", 150, InventoryPane::code));
        table.getColumns().add(Tables.<StockSnapshot>textColumn("Product", 260, StockSnapshot::name));
        table.getColumns().add(Tables.<StockSnapshot>textColumn("On Hand", 110,
                s -> Quantity.format(s.onHand()) + " " + s.unit()));
        // A reorder level of 0 never gives Low Stock (Inventory's rule), so say it is not set.
        table.getColumns().add(Tables.<StockSnapshot>textColumn("Reorder Level", 120,
                s -> s.reorderLevel().signum() == 0 ? "Not set" : Quantity.format(s.reorderLevel())));
        table.getColumns().add(Tables.<StockSnapshot>nodeColumn("Status", 130, 120, InventoryPane::statusChip));
        ReportsUi.placeholder(table, "No products match.");
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        show.getItems().setAll(ALL, LOW, OUT);
        show.setValue(ALL);
        show.setPrefWidth(170);
        show.valueProperty().addListener((obs, old, now) -> filter());
        search.setPrefWidth(260);

        Button apply = ReportsUi.applyButton(this::load);
        apply.setText("Refresh");
        HBox toolbar = new HBox(12, show, search, apply);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        view.getChildren().addAll(toolbar, table, message, ReportsUi.footer(pager,
                ReportsUi.tile("Products", totalValue), ReportsUi.tile("Low Stock", lowValue),
                ReportsUi.tile("Out of Stock", outValue)));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    @Override
    public Parent getView() {
        return view;
    }

    @Override
    public void refresh() {
        load();
    }

    private void load() {
        try {
            report = service.current(screen.sessionId());
            message.setText("");
            ReportsUi.placeholder(table, "No products match.");
        } catch (ReportException e) {
            failed(e.getMessage());
        } catch (RuntimeException e) {
            failed(UiErrors.message(e, "The stock could not be loaded. Try again."));
        }
        totalValue.setText(ReportsUi.count(report.all().size()));
        lowValue.setText(ReportsUi.count(report.lowStock().size()));
        outValue.setText(ReportsUi.count(report.outOfStock().size()));
        filter();
    }

    /** Shows the chosen list, narrowed by the search box. Nothing is read again. */
    private void filter() {
        Show which = show.getValue() == null ? Show.ALL : show.getValue().value();
        List<StockSnapshot> list = switch (which) {
            case ALL -> report.all();
            case LOW -> report.lowStock();
            case OUT -> report.outOfStock();
        };
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        rows = q.isEmpty() ? list : list.stream()
                .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(q)
                        || code(s).toLowerCase(Locale.ROOT).contains(q))
                .toList();
        pager.reset();
        showPage();
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    private void failed(String text) {
        report = new InventoryReport(List.of(), List.of(), List.of());
        ReportsUi.placeholder(table, ReportsUi.NOT_LOADED);
        message.setText(text);
        Styles.error(message);
    }

    /** The barcode, or the product ID when it has none (as on Inventory's screen). */
    private static String code(StockSnapshot s) {
        return s.barcode() == null || s.barcode().isBlank() ? s.productId() : s.barcode();
    }

    /** Green / amber / red chip with the words (style guide: never colour alone). */
    private static Node statusChip(StockSnapshot s) {
        Label chip = new Label(switch (s.level()) {
            case IN_STOCK -> "In Stock";
            case LOW_STOCK -> "Low Stock";
            case OUT_OF_STOCK -> "Out of Stock";
        });
        String colour = switch (s.level()) {
            case IN_STOCK -> Theme.SUCCESS;
            case LOW_STOCK -> Theme.WARNING;
            case OUT_OF_STOCK -> Theme.ERROR;
        };
        Styles.pill(chip, colour, "white");
        HBox box = new HBox(chip);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
