package reports.service;

import pos.contract.NetSale;
import pos.contract.ReturnHistory;
import pos.contract.ReturnRecord;
import pos.contract.SaleHistory;
import pos.contract.SaleRecord;
import reports.model.ReportPeriod;

import java.util.List;

/**
 * Loads a period's completed sales for the sales report services: the one place that calls
 * {@link SaleHistory#findCompleted} and turns a database problem into a {@link ReportException}.
 */
final class SalesSource {

    static final String NOT_LOADED = "The sales could not be loaded. Check the database connection and try again.";

    private SalesSource() { }

    /** The period's sales, oldest first. */
    static List<SaleRecord> completedIn(SaleHistory history, ReportPeriod period) throws ReportException {
        try {
            return history.findCompleted(period.start(), period.end());
        } catch (RuntimeException e) {
            throw new ReportException(NOT_LOADED, e);
        }
    }

    /**
     * The period's sales, oldest first, each with its refunds taken off ({@link NetSale}): a refund
     * lowers the sales of the day the SALE was made, whenever the refund was. {@code returns} null =
     * no refunds are known (the sales as sold).
     */
    static List<NetSale> netIn(SaleHistory history, ReturnHistory returns, ReportPeriod period) throws ReportException {
        List<SaleRecord> sales = completedIn(history, period);
        if (returns == null || sales.isEmpty()) {
            return NetSale.of(sales, List.of());
        }
        List<ReturnRecord> refunds;
        try {
            refunds = returns.findForSalesCompletedIn(period.start(), period.end());
        } catch (RuntimeException e) {
            throw new ReportException(NOT_LOADED, e);
        }
        return NetSale.of(sales, refunds);
    }
}
