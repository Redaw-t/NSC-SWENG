package pos.cart;

import pos.contract.PriceType;
import pos.contract.ProductInfo;

import java.math.BigDecimal;

/**
 * The store's pricing rule (requirement FR-03): a sale line of
 * {@link #WHOLESALE_MIN_QTY} or more of the same product is charged the
 * product's wholesale price; below that, the retail price. A product with no
 * wholesale price is always charged retail.
 */
public final class ThresholdPricingRule implements PricingRule {

    /** The ONE place the wholesale threshold is set. */
    public static final int WHOLESALE_MIN_QTY = 6;

    private final BigDecimal threshold;

    /** The store's rule: wholesale from {@value #WHOLESALE_MIN_QTY} units. */
    public ThresholdPricingRule() {
        this(WHOLESALE_MIN_QTY);
    }

    /** A rule with a different threshold (tests, or if the store changes it). */
    public ThresholdPricingRule(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be at least 1");
        }
        this.threshold = BigDecimal.valueOf(threshold);
    }

    @Override
    public PriceType priceType(BigDecimal quantity) {
        return quantity.compareTo(threshold) >= 0 ? PriceType.WHOLESALE : PriceType.RETAIL;
    }

    @Override
    public BigDecimal unitPrice(ProductInfo product, BigDecimal quantity) {
        if (priceType(quantity) == PriceType.WHOLESALE && product.hasWholesalePrice()) {
            return product.wholesalePrice();
        }
        return product.retailPrice();
    }
}
