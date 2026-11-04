package inventory.contract;

import java.util.List;

/**
 * CONTRACT — implemented by the Inventory module (InventoryService), read by Transaction &amp;
 * Reports for the inventory report (current stock, low stock, out of stock).
 *
 * Read-only. It does not check who is asking: the caller checks its own permission
 * (Reports needs VIEW_INVENTORY_REPORTS). The low-stock rule stays in Inventory: each
 * {@link StockSnapshot} arrives with its level already worked out.
 *
 * Database problems are reported as RuntimeException (like StockLevels).
 */
public interface StockOverview {

    /**
     * Every active product, plus any product that has a stock row, sorted by name (the same rows
     * as Inventory's Current Stock screen). A product never stocked shows 0 on hand.
     */
    List<StockSnapshot> currentStock();
}
