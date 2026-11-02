package inventory.model;

import java.math.BigDecimal;

/**
 * Where a product stands against its reorder level. The label is what the
 * status chip on the Current Stock screen shows (never colour alone).
 */
public enum StockStatus {
    IN_STOCK("In Stock"),
    LOW_STOCK("Low Stock"),
    OUT_OF_STOCK("Out of Stock");

    private final String label;

    StockStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Nothing left = out of stock. At or below the reorder level = low stock.
     * (A reorder level of 0 therefore never shows "Low Stock".)
     */
    public static StockStatus of(BigDecimal onHand, BigDecimal reorderLevel) {
        if (onHand.signum() <= 0) {
            return OUT_OF_STOCK;
        }
        if (onHand.compareTo(reorderLevel) <= 0) {
            return LOW_STOCK;
        }
        return IN_STOCK;
    }
}
