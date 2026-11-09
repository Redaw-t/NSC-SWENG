package pos.contract;

import java.util.List;
import java.util.Optional;

/**
 * CONTRACT — read-only access to voids / returns (tables Return, ReturnLine). Implemented by POS;
 * used by POS (retrieved sale, invoice reprint) and available to Reports.
 *
 * Database problems are reported as RuntimeException; "not found" is empty, never an exception.
 */
public interface ReturnHistory {

    /** Every return of the sale with this UUID, oldest first (...-01 first). */
    List<ReturnRecord> findBySaleId(String saleId);

    /** The return with this number, e.g. "T01-2026-000123-01", or as its barcode holds it ("T01202600012301"). */
    Optional<ReturnRecord> findByReturnNo(String returnNo);

    /** Returns made with {@code from <= returnedAt < to}, oldest first (Reports' transaction history). */
    List<ReturnRecord> findReturned(java.time.Instant from, java.time.Instant to);

    /**
     * The returns of the sales COMPLETED with {@code from <= completedAt < to}, whenever they were made,
     * oldest first. With {@link NetSale} this takes a refund off the sales of the day of the sale.
     */
    List<ReturnRecord> findForSalesCompletedIn(java.time.Instant from, java.time.Instant to);
}
