package system.repository;

import system.model.InventoryLogEntry;
import system.model.InventoryLogFilter;

import java.util.List;

/**
 * Reads Inventory's stock movement history for the Inventory Logs tab. Read-only: stock
 * movements are written by Inventory alone. Database problems are RuntimeException.
 */
public interface InventoryLogRepository {

    /** Movements matching {@code filter}, newest first, at most {@code limit}. */
    List<InventoryLogEntry> find(InventoryLogFilter filter, int limit);
}
