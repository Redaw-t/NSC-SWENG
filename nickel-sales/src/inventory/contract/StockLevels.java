package inventory.contract;

import java.math.BigDecimal;

/**
 * CONTRACT — implemented by the Inventory module, read by the Product module
 * (to fill {@code ProductInfo.stockOnHand}) and by anyone else who needs the
 * current stock of a product.
 *
 * Read-only on purpose: stock only changes through Inventory's own screens
 * and through {@link StockRecorder} (a completed sale).
 *
 * Database problems are reported as RuntimeException (like ProductLookup).
 */
public interface StockLevels {

    /**
     * Units on hand for the product, in the product's unit. Zero when the
     * product has never been stocked or the ID is unknown. Never negative.
     */
    BigDecimal available(String productId);
}
