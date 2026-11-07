package system.repository;

import system.model.SaleLineLogEntry;
import system.model.SaleLineLogFilter;

import java.util.List;

/**
 * Reads the open item and edited-price lines of completed sales for the "Open Items &amp; Price Edits"
 * tab. Read-only: sale lines are written by POS alone. Database problems are RuntimeException.
 */
public interface SaleLineLogRepository {

    /** Lines matching {@code filter}, newest sale first, at most {@code limit}. */
    List<SaleLineLogEntry> find(SaleLineLogFilter filter, int limit);
}
