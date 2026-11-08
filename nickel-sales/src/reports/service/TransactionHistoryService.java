package reports.service;

import auth.permission.Permission;
import pos.contract.ReturnHistory;
import pos.contract.ReturnRecord;
import pos.contract.SaleHistory;
import pos.contract.SaleRecord;
import reports.model.ReportFilter;
import reports.model.TransactionList;
import reports.model.TransactionRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Transaction history: the completed sales of a period, filtered, and one sale with its lines
 * and payments. With a {@link ReturnHistory}, the period's RETURNS (POS voids) are listed too, as
 * their own rows with a minus total. Read-only. Needs VIEW_SALES_REPORTS.
 */
public final class TransactionHistoryService {

    private final SaleHistory history;
    private final ReturnHistory returns;   // null = sales only
    private final ReportsAccess access;

    public TransactionHistoryService(SaleHistory history, ReportsAccess access) {
        this(history, null, access);
    }

    public TransactionHistoryService(SaleHistory history, ReturnHistory returns, ReportsAccess access) {
        this.history = Objects.requireNonNull(history, "history");
        this.returns = returns;
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * The sales and returns matching {@code filter}, newest first. An exact invoice number is found
     * on any date (with that sale's returns), and so is an exact return number; otherwise the period's
     * sales and returns are searched (see {@link ReportFilter}).
     */
    public TransactionList find(String sessionId, ReportFilter filter) throws ReportException {
        access.require(sessionId, Permission.VIEW_SALES_REPORTS);
        Objects.requireNonNull(filter, "filter");

        Optional<SaleRecord> exact = Optional.empty();
        Optional<ReturnRecord> exactReturn = Optional.empty();
        if (filter.invoiceNo() != null) {
            try {
                exact = history.findByInvoiceNo(filter.invoiceNo());
                if (exact.isEmpty() && returns != null) {
                    exactReturn = returns.findByReturnNo(filter.invoiceNo().toUpperCase(Locale.ROOT));
                }
            } catch (RuntimeException e) {
                throw new ReportException(SalesSource.NOT_LOADED, e);
            }
        }

        List<SaleRecord> candidates;
        List<ReturnRecord> returnCandidates;
        try {
            if (exact.isPresent()) {
                candidates = List.of(exact.get());
                returnCandidates = returns == null ? List.of() : returns.findBySaleId(exact.get().saleId());
            } else if (exactReturn.isPresent()) {
                candidates = List.of();
                returnCandidates = List.of(exactReturn.get());
            } else {
                candidates = SalesSource.completedIn(history, filter.period());
                returnCandidates = returns == null ? List.of()
                        : returns.findReturned(filter.period().start(), filter.period().end());
            }
        } catch (RuntimeException e) {
            throw new ReportException(SalesSource.NOT_LOADED, e);
        }
        boolean exactHit = exact.isPresent() || exactReturn.isPresent();

        TreeSet<String> cashiers = new TreeSet<>();
        TreeSet<String> terminals = new TreeSet<>();
        List<SaleRecord> sales = new ArrayList<>();
        List<TransactionRow> rows = new ArrayList<>();
        Map<String, SaleRecord> byId = new HashMap<>();
        for (SaleRecord sale : candidates) {
            byId.put(sale.saleId(), sale);
            cashiers.add(sale.cashierUserId());
            terminals.add(sale.terminalId());
            if (filter.matches(sale) && (exactHit || filter.invoiceContains(sale))) {
                sales.add(sale);
                rows.add(TransactionRow.of(sale));
            }
        }
        for (ReturnRecord ret : returnCandidates) {
            SaleRecord sale = byId.computeIfAbsent(ret.saleId(), id -> saleOf(id));   // may be from an earlier day
            if (sale == null) {
                continue;
            }
            TransactionRow row = new TransactionRow(sale, ret);
            cashiers.add(row.cashierId());
            terminals.add(row.terminalId());
            if (filter.matches(row) && (exactHit || filter.numberContains(row.number()))) {
                rows.add(row);
            }
        }
        sales.sort(Comparator.comparing(SaleRecord::completedAt).reversed());
        rows.sort(Comparator.comparing(TransactionRow::when).reversed());
        return new TransactionList(sales, rows, new ArrayList<>(cashiers), new ArrayList<>(terminals));
    }

    private SaleRecord saleOf(String saleId) {
        try {
            return history.findById(saleId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One sale, to show its lines and payments. */
    public Optional<SaleRecord> open(String sessionId, String saleId) throws ReportException {
        access.require(sessionId, Permission.VIEW_SALES_REPORTS);
        if (saleId == null || saleId.isBlank()) {
            return Optional.empty();
        }
        try {
            return history.findById(saleId.trim());
        } catch (RuntimeException e) {
            throw new ReportException("The sale could not be loaded. Try again.", e);
        }
    }

    /** One return, to show its summary; read again so a user who lost the permission cannot open it. */
    public Optional<ReturnRecord> openReturn(String sessionId, String returnNo) throws ReportException {
        access.require(sessionId, Permission.VIEW_SALES_REPORTS);
        if (returns == null || returnNo == null || returnNo.isBlank()) {
            return Optional.empty();
        }
        try {
            return returns.findByReturnNo(returnNo.trim());
        } catch (RuntimeException e) {
            throw new ReportException("The return could not be loaded. Try again.", e);
        }
    }
}
