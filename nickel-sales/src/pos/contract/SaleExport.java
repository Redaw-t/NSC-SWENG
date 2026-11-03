package pos.contract;

import java.time.Instant;
import java.util.List;

/**
 * CONTRACT — implemented by POS (Phase 2), called by the Sync module.
 *
 * Sync asks for completed sales it has not sent yet, sends them, and marks
 * each one as synced. POS never changes a completed sale, so a sale can be
 * sent as-is; {@link SaleRecord#saleId()} is unique across all terminals.
 *
 * Database problems are reported as RuntimeException.
 */
public interface SaleExport {

    /** Completed sales not yet marked synced, oldest first, at most {@code limit}. */
    List<SaleRecord> findUnsynced(int limit);

    /** Records that the sale was sent. Safe to call again for the same sale. */
    void markSynced(String saleId, Instant syncedAt);
}
