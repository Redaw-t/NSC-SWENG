package system.model;

import pos.receipt.StoreInfo;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of the StoreDetails log: the store details as they were set by one change. The newest row
 * is the one printed on receipts; older rows are kept as the history of who changed what.
 *
 * @param id          database ID (StoreDetailsID); 0 until saved
 * @param store       the details: name, address, proprietor, TIN, phone
 * @param performedBy the admin who made the change (a user ID, e.g. NSC-00001)
 * @param performedAt when (UTC)
 * @param terminalId  the computer it was changed on (T01, T02 ...)
 */
public record StoreDetailsRecord(long id, StoreInfo store, String performedBy, Instant performedAt,
                                 String terminalId) {

    public StoreDetailsRecord {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(performedBy, "performedBy");
        Objects.requireNonNull(performedAt, "performedAt");
        Objects.requireNonNull(terminalId, "terminalId");
    }
}
