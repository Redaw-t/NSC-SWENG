package reports.ui;

import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import reports.model.GroupTotal;
import reports.model.ReportPeriod;
import reports.service.CashierReportService;
import reports.service.ReportException;
import reports.ui.ReportsUi.Choice;
import shared.Money;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.UiErrors;

import java.math.BigDecimal;
import java.util.List;

/**
 * The "Cashiers &amp; Terminals" tab: sales per cashier (shown by name) or per terminal for a
 * period, highest total first.
 */
final class CashierPane implements ReportsPane {

    private static final Choice<Boolean> BY_CASHIER = new Choice<>(true, "By cashier");
    private static final Choice<Boolean> BY_TERMINAL = new Choice<>(false, "By terminal");

    private final ScreenContext screen;
    private final CashierReportService service;
    private final UserNames names;

    private final PeriodControls period = new PeriodControls(this::load);
    private final SearchPicker<Choice<Boolean>> groupBy = new SearchPicker<>(false, false);
    private final TableView<GroupTotal> table = Tables.create();
    private final TableColumn<GroupTotal, String> keyColumn;
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label countValue = new Label("0");
    private final Label totalValue = new Label(ReportsUi.peso(Money.ZERO));
    private final Label message = ReportsUi.messageLabel();
    private final VBox view = new VBox(16);

    private List<GroupTotal> rows = List.of();
    private boolean showingCashiers = true;

    CashierPane(ScreenContext screen, CashierReportService service) {
        this.screen = screen;
        this.service = service;
        this.names = new UserNames(screen);

        table.getColumns().add(Tables.numberColumn(pager));
        keyColumn = Tables.textColumn("Cashier", 260, g -> showingCashiers ? names.label(g.key()) : g.key());
        table.getColumns().add(keyColumn);
        table.getColumns().add(Tables.<GroupTotal>textColumn("Transactions", 120, g -> ReportsUi.count(g.transactions())));
        table.getColumns().add(Tables.<GroupTotal>textColumn("Total Sales", 160, g -> ReportsUi.peso(g.total())));
        ReportsUi.placeholder(table, "No sales in this period.");
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        groupBy.getItems().setAll(BY_CASHIER, BY_TERMINAL);
        groupBy.setValue(BY_CASHIER);
        groupBy.setPrefWidth(160);

        shared.LiveFilters filters = new shared.LiveFilters(this::load);   // no Apply: it updates as you choose
        period.watch(filters);
        filters.watch(groupBy);
        Button reset = filters.resetButton(() -> {
            period.reset();
            groupBy.setValue(BY_CASHIER);
        });
        FlowPane toolbar = new FlowPane(12, 12, period.getView(), groupBy, reset, filters.refreshIcon());
        toolbar.setAlignment(Pos.CENTER_LEFT);

        view.getChildren().addAll(toolbar, table, message, ReportsUi.footer(pager,
                ReportsUi.tile("Transactions", countValue), ReportsUi.tile("Total Sales", totalValue)));
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
        ReportPeriod p;
        try {
            p = period.period();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        showingCashiers = groupBy.getValue() == null || groupBy.getValue().value();
        keyColumn.setText(showingCashiers ? "Cashier" : "Terminal");
        try {
            rows = showingCashiers ? service.byCashier(screen.sessionId(), p) : service.byTerminal(screen.sessionId(), p);
            if (showingCashiers) {
                rows.forEach(g -> names.remember(g.key()));
            }
            message.setText("");
        } catch (ReportException e) {
            rows = List.of();
            showError(e.getMessage());
        } catch (RuntimeException e) {
            rows = List.of();
            showError(UiErrors.message(e, "The report could not be loaded. Try again."));
        }
        int count = 0;
        BigDecimal total = Money.ZERO;
        for (GroupTotal g : rows) {
            count += g.transactions();
            total = total.add(g.total());
        }
        countValue.setText(ReportsUi.count(count));
        totalValue.setText(ReportsUi.peso(total));
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
