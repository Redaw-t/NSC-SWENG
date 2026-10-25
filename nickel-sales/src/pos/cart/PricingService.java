package pos.cart;

import pos.contract.PriceType;
import pos.contract.ProductInfo;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Prices one sale line (PricingService in the class diagram). The cart calls
 * this every time a line's quantity changes, so a line switches between
 * retail and wholesale on its own (e.g. 5 -> 6 pieces).
 */
public final class PricingService {

    private final PricingRule rule;

    /** Uses the store's rule ({@link ThresholdPricingRule}). */
    public PricingService() {
        this(new ThresholdPricingRule());
    }

    public PricingService(PricingRule rule) {
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    /**
     * The line for {@code quantity} of {@code product}, with the unit price and
     * price type the rule gives. A product with no wholesale price is always
     * recorded as RETAIL, whatever the quantity.
     */
    public SaleLineItem priceLine(ProductInfo product, BigDecimal quantity) {
        BigDecimal unitPrice = rule.unitPrice(product, quantity);
        PriceType type = rule.priceType(quantity);
        if (type == PriceType.WHOLESALE && !product.hasWholesalePrice()) {
            type = PriceType.RETAIL;
        }
        return new SaleLineItem(product.productId(), product.barcode(), product.name(), product.unit(),
                quantity, unitPrice, type, product.vatable());
    }
}
