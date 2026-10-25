package pos.cart;

import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A line in the open sale that can't be sold as it stands right now: the
 * cart asks for more than is in stock (stock can drop after an item was
 * added, e.g. the other terminal sold some), or the product is no longer sold.
 *
 * Found by {@link SalesService#findStockProblems()}. While there are any, the
 * screen greys out Pay and shows {@link #message()} next to the line; the
 * cashier fixes it by lowering the quantity to {@code available} or less, or
 * removing the line.
 *
 * @param inCart       the quantity on the line
 * @param available    the stock on hand now (0 if the product is gone)
 * @param noLongerSold the product was deactivated (or deleted) since it was added
 */
public record StockProblem(String productId, String productName, BigDecimal inCart, BigDecimal available,
                           boolean noLongerSold) {

    public StockProblem {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productName, "productName");
        inCart = Quantity.normalize(Objects.requireNonNull(inCart, "inCart"));
        available = Quantity.normalize(Objects.requireNonNull(available, "available")).max(BigDecimal.ZERO.setScale(3));
    }

    /** Ready to show the cashier, worded like InsufficientStockException. */
    public String message() {
        if (noLongerSold) {
            return productName + " is no longer sold. Remove it from the sale.";
        }
        return "Not enough stock for " + productName + ": " + Quantity.format(available) + " left, "
                + Quantity.format(inCart) + " in the sale. Lower the quantity or remove it.";
    }
}
