package reports.ui;

import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.SaleRecord;
import reports.model.ReportFilter;
import reports.model.ReportPeriod;
import reports.model.TransactionList;
import reports.service.ReportException;
import reports.service.TransactionHistoryService;
import reports.ui.ReportsUi.Choice;
import shared.Money;
import shared.PhTime;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.UiErrors;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The "Transactions" tab: completed sales of a period (default today), filtered by cashier,
 * terminal, payment method and invoice number, newest first. View opens a sale's lines and
 * payments. Read-only.
 */
final class TransactionsPane implements ReportsPane {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy h:mm a", Locale.US);
    private static final String EMPTY = "No sales found for these filters.";

    private final ScreenContext screen;
    private final TransactionHistoryService history;
    private final UserNames names;

    private final PeriodControls period = new PeriodControls(this::load);
    private final SearchPicker<Choice<String>> cashier = new SearchPicker<>(false, true);
    private final SearchPicker<Choice<String>> terminal = new SearchPicker<>(false, false);
    private final SearchPicker<Choice<PaymentMethod>> method = new SearchPicker<>(false, false);
    private final TextField invoice = ReportsUi.searchField("Invoice no.", this::load);
    private final TableView<SaleRecord> table = Tables.create();
    private final TablePager pager = TablePager.links(this::showPage);
    private final Label countValue = new Label("0");
    private final Label totalValue = new Label(ReportsUi.peso(Money.ZERO));
    private final Label message = ReportsUi.messageLabel();
    private final VBox view = new VBox(16);

    private List<SaleRecord> rows = List.of();

    TransactionsPane(ScreenContext screen, TransactionHistoryService history) {
        this.screen = screen;
        this.history = history;
        this.names = new UserNames(screen);

        table.getColumns().add(Tables.numberColumn(pager));
        table.getColumns().add(Tables.<SaleRecord>textColumn("Date / Time", 130,
                s -> ROW_TIME.format(PhTime.of(s.completedAt()))));
        table.getColumns().add(Tables.<SaleRecord>textColumn("Invoice No.", 150, SaleRecord::invoiceNo));
        table.getColumns().add(Tables.<SaleRecord>textColumn("Cashier", 170, s -> names.name(s.cashierUserId())));
        table.getColumns().add(Tables.<SaleRecord>textColumn("Terminal", 80, SaleRecord::terminalId));
        table.getColumns().add(Tables.<SaleRecord>textColumn("Payment", 100, TransactionsPane::methods));
        table.getColumns().add(Tables.<SaleRecord>textColumn("Items", 60, s -> String.valueOf(s.lines().size())));
        table.getColumns().add(Tables.<SaleRecord>textColumn("Total", 110, s -> ReportsUi.peso(s.total())));
        table.getColumns().add(Tables.<SaleRecord>nodeColumn("Actions", 90, 80,
                s -> ReportsUi.viewButton(() -> showDetails(s))));
        ReportsUi.placeholder(table, EMPTY);
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        cashier.setPrefWidth(200);
        terminal.setPrefWidth(140);
        method.setPrefWidth(150);
        setChoices(cashier, "All Cashiers", List.of(), id -> id);
        setChoices(terminal, "All Terminals", List.of(), id -> id);
        List<Choice<PaymentMethod>> methods = new ArrayList<>();
        methods.add(new Choice<>(null, "All Payments"));
        for (PaymentMethod m : PaymentMethod.values()) {
            methods.add(new Choice<>(m, methodLabel(m)));
        }
        method.getItems().setAll(methods);
        method.setValue(methods.get(0));

        Button apply = ReportsUi.applyButton(this::load);
        FlowPane toolbar = new FlowPane(12, 12, period.getView(), cashier, terminal, method, invoice, apply);
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
        ReportFilter filter;
        try {
            ReportPeriod p = period.period();
            filter = new ReportFilter(p, valueOf(cashier), valueOf(terminal),
                    method.getValue() == null ? null : method.getValue().value(), invoice.getText());
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        try {
            TransactionList list = history.find(screen.sessionId(), filter);
            rows = list.sales();
            list.cashierIds().forEach(names::remember);
            setChoices(cashier, "All Cashiers", list.cashierIds(), names::label);
            setChoices(terminal, "All Terminals", list.terminalIds(), id -> id);
            ReportsUi.placeholder(table, EMPTY);
            message.setText(filter.invoiceNo() != null && rows.size() == 1
                    && rows.get(0).invoiceNo().equalsIgnoreCase(filter.invoiceNo())
                    && !filter.period().contains(rows.get(0).completedAt())
                    ? "Invoice " + rows.get(0).invoiceNo() + " is from " + PhTime.format(rows.get(0).completedAt())
                    + ", outside the chosen dates." : "");
            Styles.muted(message);
        } catch (ReportException e) {
            loadFailed(e.getMessage());
        } catch (RuntimeException e) {
            loadFailed(UiErrors.message(e, "The sales could not be loaded. Try again."));
        }
        BigDecimal total = Money.ZERO;
        for (SaleRecord s : rows) {
            total = total.add(s.total());
        }
        countValue.setText(ReportsUi.count(rows.size()));
        totalValue.setText(ReportsUi.peso(total));
        pager.reset();
        showPage();
    }

    private void showDetails(SaleRecord row) {
        try {
            // Read again through the service, so a user who lost the permission cannot open it.
            SaleRecord sale = history.open(screen.sessionId(), row.saleId()).orElse(row);
            names.remember(sale.cashierUserId());
            SaleDetailsDialog.show(screen.stage(), sale, names.label(sale.cashierUserId()));
        } catch (ReportException e) {
            showError(e.getMessage());
        }
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    private void loadFailed(String text) {
        rows = List.of();
        ReportsUi.placeholder(table, ReportsUi.NOT_LOADED);
        showError(text);
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }

    /**
     * Fills a drop-down with "All ..." and {@code ids}, keeping the current choice (even if it
     * made no sale in the new period, so the filter does not silently change).
     */
    private static void setChoices(SearchPicker<Choice<String>> picker, String all, List<String> ids,
                                   java.util.function.Function<String, String> label) {
        String chosen = valueOf(picker);
        List<Choice<String>> items = new ArrayList<>();
        items.add(new Choice<>(null, all));
        for (String id : ids) {
            items.add(new Choice<>(id, label.apply(id)));
        }
        if (chosen != null && !ids.contains(chosen)) {
            items.add(new Choice<>(chosen, label.apply(chosen)));
        }
        picker.getItems().setAll(items);
        picker.setValue(items.stream().filter(c -> Objects.equals(c.value(), chosen)).findFirst().orElse(items.get(0)));
    }

    private static String valueOf(SearchPicker<Choice<String>> picker) {
        return picker.getValue() == null ? null : picker.getValue().value();
    }

    private static String methods(SaleRecord sale) {
        return String.join(", ", sale.payments().stream().map(PaymentRecord::method).distinct()
                .map(TransactionsPane::methodLabel).toList());
    }

    /** "Cash", "GCash", "Maya", "QRPh". */
    static String methodLabel(PaymentMethod m) {
        return switch (m) {
            case CASH -> "Cash";
            case GCASH -> "GCash";
            case MAYA -> "Maya";
            case QRPH -> "QRPh";
        };
    }
}
