package inventory.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of the Current Stock screen: a product joined with its stock level.
 * Products that were never stocked appear with 0 on hand and no update time.
 */
public record StockRow(String productId, String name, String barcode, String unit,
                       BigDecimal onHand, BigDecimal reorderLevel, Instant updatedAt) {

    /** The barcode, or the product ID when it has none (for pickers, where the ID tells products apart). */
    public String barcodeOrId() {
        return barcode == null || barcode.isBlank() ? productId : barcode;
    }

    /** True when the product has a barcode. */
    public boolean hasBarcode() {
        return barcode != null && !barcode.isBlank();
    }

    public StockStatus status() {
        return StockStatus.of(onHand, reorderLevel);
    }
}
