package pos.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CONTRACT — implemented by POS (Phase 2, together with SaleExport), called by
 * Transaction &amp; Reports (transaction history, sales / cashier / product
 * reports) and by POS itself (receipt reprint).
 *
 * The read side of SaleRepository in the class diagram. Read-only: completed
 * sales never change, and only POS saves them. Other modules read sales
 * through this interface rather than the Sale / SaleLine / Payment tables,
 * so POS can change how sales are stored without breaking reports.
 *
 * To get a day, month or custom period in Philippine time, convert it with
 * {@code shared.PhTime.startOf(...)}.
 *
 * Database problems are reported as RuntimeException; "not found" is an
 * empty Optional / empty list, never an exception.
 */
public interface SaleHistory {

    /** The sale with this UUID. */
    Optional<SaleRecord> findById(String saleId);

    /** The sale with this invoice number, e.g. "T01-2026-000123". */
    Optional<SaleRecord> findByInvoiceNo(String invoiceNo);

    /**
     * Completed sales with {@code from <= completedAt < to}, oldest first.
     * Filter by cashier, terminal or product on the returned records.
     */
    List<SaleRecord> findCompleted(Instant from, Instant to);
}
