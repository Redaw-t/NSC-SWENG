package inventory.model;

import shared.Quantity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of the stock movement history: a single change to a product's stock.
 * Movements are only ever added, never edited or deleted.
 *
 * Like POS's SaleRecord, it refuses to be built if the numbers do not add up:
 * {@code quantityAfter = quantityBefore + quantity}, and the sign of
 * {@code quantity} matches the type (in = plus, out and sale = minus).
 *
 * @param movementId  database ID; 0 until saved
 * @param quantity    SIGNED change: +20 for a stock-in, -2 for a sale
 * @param reason      Stock Out / Adjust Stock reason; null for stock-in and sales
 * @param referenceNo system reference such as the invoice number of a sale; may be null
 * @param note        what the person typed (the stock-in "Reference / Note", an adjustment note); may be null
 * @param performedBy user ID of who did it; null for sales (POS does not pass the cashier yet)
 */
public record StockMovement(long movementId, String productId, MovementType type,
                            BigDecimal quantity, BigDecimal quantityBefore, BigDecimal quantityAfter,
                            StockReason reason, String referenceNo, String note,
                            String performedBy, Instant createdAt) {

    public StockMovement {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(quantityBefore, "quantityBefore");
        Objects.requireNonNull(quantityAfter, "quantityAfter");
        Objects.requireNonNull(createdAt, "createdAt");
        quantity = Quantity.normalize(quantity);
        quantityBefore = Quantity.normalize(quantityBefore);
        quantityAfter = Quantity.normalize(quantityAfter);
        if (quantity.signum() == 0) {
            throw new IllegalArgumentException("A stock movement cannot be zero: " + productId);
        }
        if (quantityBefore.signum() < 0 || quantityAfter.signum() < 0) {
            throw new IllegalArgumentException("Stock cannot be negative: " + productId);
        }
        if (quantityBefore.add(quantity).compareTo(quantityAfter) != 0) {
            throw new IllegalArgumentException("Before + quantity must equal after: " + productId);
        }
        boolean plus = quantity.signum() > 0;
        boolean signOk = switch (type) {
            case STOCK_IN -> plus;
            case STOCK_OUT, SALE -> !plus;
            case ADJUSTMENT -> true;
        };
        if (!signOk) {
            throw new IllegalArgumentException(type.label() + " has the wrong sign: " + productId);
        }
    }
}
