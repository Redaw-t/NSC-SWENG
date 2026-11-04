package barcode.service;

import product.contract.ProductInfo;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One line of the Barcode screen.
 *
 * @param product     the product
 * @param status      assigned / unassigned / error
 * @param problem     why the status is ERROR (ready to show), otherwise null
 * @param lastUpdated when the product record last changed; null when unknown
 */
public record BarcodeRow(ProductInfo product, BarcodeStatus status, String problem, LocalDateTime lastUpdated) {

    public BarcodeRow {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(status, "status");
    }

    /** The barcode, or null when the product has none. */
    public String barcode() {
        return product.barcode() == null || product.barcode().isBlank() ? null : product.barcode().trim();
    }

    /** True when a label can be printed for this row. */
    public boolean printable() {
        return status == BarcodeStatus.ASSIGNED;
    }
}
