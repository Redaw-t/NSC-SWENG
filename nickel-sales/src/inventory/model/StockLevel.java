package inventory.model;

import shared.Quantity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * How much of one product is on hand, and when to reorder it.
 *
 * @param productId      the product's unique ID (same ID as the Product module's)
 * @param quantityOnHand units in stock, never negative
 * @param reorderLevel   at or below this the product counts as low stock
 * @param updatedAt      last change (UTC); null for a product never stocked
 */
public record StockLevel(String productId, BigDecimal quantityOnHand,
                         BigDecimal reorderLevel, Instant updatedAt) {

    public StockLevel {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantityOnHand, "quantityOnHand");
        Objects.requireNonNull(reorderLevel, "reorderLevel");
        quantityOnHand = Quantity.normalize(quantityOnHand);
        reorderLevel = Quantity.normalize(reorderLevel);
        if (quantityOnHand.signum() < 0) {
            throw new IllegalArgumentException("Stock cannot be negative: " + productId);
        }
        if (reorderLevel.signum() < 0) {
            throw new IllegalArgumentException("Reorder level cannot be negative: " + productId);
        }
    }

    public StockStatus status() {
        return StockStatus.of(quantityOnHand, reorderLevel);
    }

    public StockLevel withQuantity(BigDecimal newQuantity, Instant at) {
        return new StockLevel(productId, newQuantity, reorderLevel, at);
    }

    public StockLevel withReorderLevel(BigDecimal newReorderLevel, Instant at) {
        return new StockLevel(productId, quantityOnHand, newReorderLevel, at);
    }
}
