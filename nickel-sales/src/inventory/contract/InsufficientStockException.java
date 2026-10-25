package inventory.contract;

import shared.Quantity;

import java.math.BigDecimal;

/**
 * A product does not have enough stock for the quantity being sold.
 * Thrown by POS when adding to the cart, and by {@link StockRecorder} when
 * completing a sale. The message is ready to show to the cashier.
 */
public class InsufficientStockException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String productId;
    private final BigDecimal available;
    private final BigDecimal requested;

    public InsufficientStockException(String productId, String productName,
                                      BigDecimal available, BigDecimal requested) {
        super("Not enough stock for " + productName + ": "
                + Quantity.format(available.max(BigDecimal.ZERO)) + " left, "
                + Quantity.format(requested) + " needed.");
        this.productId = productId;
        this.available = available;
        this.requested = requested;
    }

    public String getProductId() {
        return productId;
    }

    public BigDecimal getAvailable() {
        return available;
    }

    public BigDecimal getRequested() {
        return requested;
    }
}
