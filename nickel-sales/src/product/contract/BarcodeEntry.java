package product.contract;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * CONTRACT — one row of the Barcode screen: a sold product and when its record last changed.
 *
 * @param product     the product (its barcode may be null: no barcode yet)
 * @param lastUpdated when the product record last changed, in the shop's local time as stored
 *                    by MySQL (no time zone conversion); null when unknown
 */
public record BarcodeEntry(ProductInfo product, LocalDateTime lastUpdated) {

    public BarcodeEntry {
        Objects.requireNonNull(product, "product");
    }
}
