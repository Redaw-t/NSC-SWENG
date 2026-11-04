package reports.service;

import auth.permission.Permission;
import pos.contract.SaleHistory;
import pos.contract.SaleRecord;
import reports.model.ReportFilter;
import reports.model.TransactionList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Transaction history: the completed sales of a period, filtered, and one sale with its lines
 * and payments. Read-only (it only holds the read-only {@link SaleHistory}). Needs VIEW_SALES_REPORTS.
 */
public final class TransactionHistoryService {

    private final SaleHistory history;
    private final ReportsAccess access;

    public TransactionHistoryService(SaleHistory history, ReportsAccess access) {
        this.history = Objects.requireNonNull(history, "history");
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * The sales matching {@code filter}, newest first. An exact invoice number is found on any
     * date; otherwise the period's sales are searched (see {@link ReportFilter}).
     */
    public TransactionList find(String sessionId, ReportFilter filter) throws ReportException {
        access.require(sessionId, Permission.VIEW_SALES_REPORTS);
        Objects.requireNonNull(filter, "filter");

        List<SaleRecord> candidates;
        Optional<SaleRecord> exact = Optional.empty();
        if (filter.invoiceNo() != null) {
            try {
                exact = history.findByInvoiceNo(filter.invoiceNo());
            } catch (RuntimeException e) {
                throw new ReportException(SalesSource.NOT_LOADED, e);
            }
        }
        if (exact.isPresent()) {
            candidates = List.of(exact.get());
        } else {
            candidates = SalesSource.completedIn(history, filter.period());
        }

        TreeSet<String> cashiers = new TreeSet<>();
        TreeSet<String> terminals = new TreeSet<>();
        List<SaleRecord> rows = new ArrayList<>();
        for (SaleRecord sale : candidates) {
            cashiers.add(sale.cashierUserId());
            terminals.add(sale.terminalId());
            if (filter.matches(sale) && (exact.isPresent() || filter.invoiceContains(sale))) {
                rows.add(sale);
            }
        }
        rows.sort(Comparator.comparing(SaleRecord::completedAt).reversed());
        return new TransactionList(rows, new ArrayList<>(cashiers), new ArrayList<>(terminals));
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
}
