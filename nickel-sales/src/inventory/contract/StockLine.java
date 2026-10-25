package inventory.contract;

import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * CONTRACT — one product and how much of it leaves stock, as passed to
 * {@link StockRecorder#recordSale}.
 *
 * @param productId the product's unique ID
 * @param quantity  amount to deduct, in the product's unit (more than zero)
 */
public record StockLine(String productId, BigDecimal quantity) {

    public StockLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
        quantity = Quantity.normalize(quantity);
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity must be more than zero: " + productId);
        }
    }
}
