package inventory.model;

import java.math.BigDecimal;

/**
 * One line of the Current Stock screen: a product joined with its stock level.
 * Products that were never stocked appear with 0 on hand.
 */
public record StockRow(String productId, String name, String barcode, String unit,
                       BigDecimal onHand, BigDecimal reorderLevel) {

    public StockStatus status() {
        return StockStatus.of(onHand, reorderLevel);
    }
}
