package inventory.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of the Current Stock screen: a product joined with its stock level.
 * Products that were never stocked appear with 0 on hand and no update time.
 */
public record StockRow(String productId, String name, String barcode, String unit,
                       BigDecimal onHand, BigDecimal reorderLevel, Instant updatedAt) {

    /** The "Product Code" column: the barcode, or the product ID when it has none. */
    public String barcodeOrId() {
        return barcode == null || barcode.isBlank() ? productId : barcode;
    }

    public StockStatus status() {
        return StockStatus.of(onHand, reorderLevel);
    }
}
