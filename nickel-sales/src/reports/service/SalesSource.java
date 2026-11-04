package reports.service;

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
}
