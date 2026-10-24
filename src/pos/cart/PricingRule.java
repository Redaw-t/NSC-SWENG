package pos.cart;

import pos.contract.PriceType;
import pos.contract.ProductInfo;

import java.math.BigDecimal;

/**
 * Decides which of a product's prices a sale line is charged at
 * (PricingRule in the class diagram). The rule the store uses is
 * {@link ThresholdPricingRule}; another rule can be swapped in through
 * {@link PricingService} without touching the cart.
 *
 * Quantities are decimals (see shared.Quantity); the diagram's int was
 * widened so items sold by kg are priced the same way.
 */
public interface PricingRule {

    /** Price per unit, before VAT, for {@code quantity} of {@code product}. */
    BigDecimal unitPrice(ProductInfo product, BigDecimal quantity);

    /** Which price a line of {@code quantity} qualifies for. */
    PriceType priceType(BigDecimal quantity);
}
