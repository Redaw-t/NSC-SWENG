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
}
