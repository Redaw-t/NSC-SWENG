package reports.model;

import inventory.contract.StockSnapshot;

import java.util.List;

/**
 * The inventory report: every product with its stock, and the two lists staff act on.
 * Low stock and out of stock do not overlap (a product with nothing left is out of stock only).
 *
 * @param all        every product, sorted by name
 * @param lowStock   at or below its reorder level, but not zero
 * @param outOfStock nothing left
 */
public record InventoryReport(List<StockSnapshot> all, List<StockSnapshot> lowStock, List<StockSnapshot> outOfStock) {

    public InventoryReport {
        all = List.copyOf(all);
        lowStock = List.copyOf(lowStock);
        outOfStock = List.copyOf(outOfStock);
    }
}
