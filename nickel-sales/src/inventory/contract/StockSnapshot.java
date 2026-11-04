package inventory.contract;

import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * CONTRACT — one product's stock at the moment {@link StockOverview#currentStock()} was called.
 *
 * @param barcode      null if the product has none
 * @param onHand       units in stock, in the product's unit; never negative
 * @param reorderLevel at or below this the product is low on stock
 * @param level        worked out by Inventory (its StockStatus rule), so readers never repeat the rule
 */
public record StockSnapshot(String productId, String name, String barcode, String unit,
                            BigDecimal onHand, BigDecimal reorderLevel, Level level) {

    /** Where the product stands against its reorder level. */
    public enum Level {
        IN_STOCK, LOW_STOCK, OUT_OF_STOCK
    }

    public StockSnapshot {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(level, "level");
        onHand = Quantity.normalize(Objects.requireNonNull(onHand, "onHand"));
        reorderLevel = Quantity.normalize(Objects.requireNonNull(reorderLevel, "reorderLevel"));
    }
}
