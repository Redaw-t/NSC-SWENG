package reports.ui;

import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import reports.model.ReportPeriod;
import reports.model.SalesBucket;
import reports.model.SalesSummary;
import reports.service.ReportException;
import reports.service.SalesReportService;
import shared.Money;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.UiErrors;

import java.util.List;

/**
 * The "Sales Summary" tab: total income, VATable / VAT-exempt / VAT, number of transactions and
 * the retail / wholesale split for a day, month, year or custom range, then one row per day (or
 * per month). All the sums are made by {@link SalesReportService#salesFor}; this only shows them.
 */
final class SalesSummaryPane implements ReportsPane {

    private static final String SPLIT_NOTE = "Retail and wholesale are line amounts, VAT included, so together they "
            + "equal the total income (older sales made when prices were before VAT do not include it).";

    private final ScreenContext screen;
    private final SalesReportService sales;

    private final PeriodControls period = new PeriodControls(this::load);
    private final Label title = new Label();
    private final Label income = new Label();
    private final Label transactions = new Label();
    private final Label vatable = new Label();
    private final Label exempt = new Label();
    private final Label vat = new Label();
    private final Label retail = new Label();
    private final Label wholesale = new Label();
    private final TableView<SalesBucket> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label message = ReportsUi.messageLabel();
    private final VBox view = new VBox(16);

    private List<SalesBucket> rows = List.of();

    SalesSummaryPane(ScreenContext screen, SalesReportService sales) {
        this.screen = screen;
        this.sales = sales;

        table.getColumns().add(Tables.<SalesBucket>textColumn("Period", 200, SalesBucket::label));
        table.getColumns().add(Tables.<SalesBucket>textColumn("Transactions", 120,
                b -> ReportsUi.count(b.transactions())));
        table.getColumns().add(Tables.<SalesBucket>textColumn("Total Sales", 160, b -> ReportsUi.peso(b.total())));
        ReportsUi.placeholder(table, "No days in this period.");
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        Button apply = ReportsUi.applyButton(this::load);
        HBox toolbar = new HBox(12, period.getView(), apply);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Styles.cardTitle(title);
        FlowPane tiles = new FlowPane(12, 12,
                ReportsUi.tile("Total Income", income), ReportsUi.tile("Transactions", transactions),
                ReportsUi.tile("VATable Sales", vatable), ReportsUi.tile("VAT-exempt Sales", exempt),
                ReportsUi.tile("VAT (12%)", vat), ReportsUi.tile("Retail", retail),
                ReportsUi.tile("Wholesale", wholesale));
        Label note = new Label(SPLIT_NOTE);
        Styles.muted(note);
        note.setWrapText(true);

        view.getChildren().addAll(toolbar, message, title, tiles, note, table, pager.getView());
        VBox.setVgrow(table, Priority.ALWAYS);
        show(null);
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
        ReportPeriod p;
        try {
            p = period.period();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        try {
            show(sales.salesFor(screen.sessionId(), p));
            message.setText("");
        } catch (ReportException e) {
            show(null);
            showError(e.getMessage());
        } catch (RuntimeException e) {
            show(null);
            showError(UiErrors.message(e, "The sales summary could not be loaded. Try again."));
        }
    }

    private void show(SalesSummary s) {
        title.setText(s == null ? "Sales Summary" : "Sales Summary - " + s.period().label());
        income.setText(ReportsUi.peso(s == null ? Money.ZERO : s.totalIncome()));
        transactions.setText(ReportsUi.count(s == null ? 0 : s.transactions()));
        vatable.setText(ReportsUi.peso(s == null ? Money.ZERO : s.vatableSales()));
        exempt.setText(ReportsUi.peso(s == null ? Money.ZERO : s.vatExemptSales()));
        vat.setText(ReportsUi.peso(s == null ? Money.ZERO : s.vatAmount()));
        retail.setText(ReportsUi.peso(s == null ? Money.ZERO : s.retailAmount()));
        wholesale.setText(ReportsUi.peso(s == null ? Money.ZERO : s.wholesaleAmount()));
        rows = s == null ? List.of() : s.breakdown();
        pager.reset();
        showPage();
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }
}
