package reports.service;

import auth.permission.Permission;
import pos.contract.SaleHistory;
import pos.contract.SaleRecord;
import reports.model.GroupTotal;
import reports.model.ReportPeriod;
import shared.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Sales per cashier and per terminal for a period, highest total first (ties: by ID).
 * Returns user IDs; the screen turns them into names ({@code AppContext.userName}), so this
 * class stays free of the user tables. Read-only. Needs VIEW_SALES_REPORTS.
 */
public final class CashierReportService {

    private final SaleHistory history;
    private final pos.contract.ReturnHistory returns;   // null = refunds not taken off
    private final ReportsAccess access;

    public CashierReportService(SaleHistory history, ReportsAccess access) {
        this(history, null, access);
    }

    /** Refunds are taken off each sale's cashier / terminal, on the day of the sale. */
    public CashierReportService(SaleHistory history, pos.contract.ReturnHistory returns, ReportsAccess access) {
        this.history = Objects.requireNonNull(history, "history");
        this.returns = returns;
        this.access = Objects.requireNonNull(access, "access");
    }

    /** One row per cashier user ID who made a sale in the period. */
    public List<GroupTotal> byCashier(String sessionId, ReportPeriod period) throws ReportException {
        return grouped(sessionId, period, SaleRecord::cashierUserId);
    }

    /** One row per terminal that made a sale in the period. */
    public List<GroupTotal> byTerminal(String sessionId, ReportPeriod period) throws ReportException {
        return grouped(sessionId, period, SaleRecord::terminalId);
    }

    private List<GroupTotal> grouped(String sessionId, ReportPeriod period, Function<SaleRecord, String> key)
            throws ReportException {
        access.require(sessionId, Permission.VIEW_SALES_REPORTS);
        Objects.requireNonNull(period, "period");
        Map<String, Integer> counts = new HashMap<>();
        Map<String, BigDecimal> totals = new HashMap<>();
        for (pos.contract.NetSale sale : SalesSource.netIn(history, returns, period)) {
            String k = key.apply(sale.sale());
            counts.merge(k, 1, Integer::sum);
            totals.merge(k, sale.total(), BigDecimal::add);   // refunds taken off
        }
        List<GroupTotal> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            rows.add(new GroupTotal(e.getKey(), e.getValue(), totals.getOrDefault(e.getKey(), Money.ZERO)));
        }
        rows.sort(Comparator.comparing(GroupTotal::total).reversed().thenComparing(GroupTotal::key));
        return rows;
    }
}
