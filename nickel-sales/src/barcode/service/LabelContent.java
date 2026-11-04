package barcode.service;

import product.contract.ProductInfo;
import shared.Money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What goes on one product label: the product name, its barcode, and the retail and wholesale
 * price (either may be left off: null). Prices are what the customer PAYS at the counter: products are stored with
 * prices before VAT and POS adds 12% on VATable items, so the label adds it here too.
 *
 * @param retailPrice    null = not printed
 * @param wholesalePrice null = not printed (or the product has none)
 */
public record LabelContent(String productName, String barcode, BigDecimal retailPrice, BigDecimal wholesalePrice) {

    /**
     * Philippine VAT, as POS adds it (pos.cart.Vat; the Barcode module may not depend on POS).
     * Set {@link #SHOW_PRICES_WITH_VAT} to false to print the prices before VAT instead.
     */
    private static final BigDecimal VAT_RATE = new BigDecimal("0.12");
    public static final boolean SHOW_PRICES_WITH_VAT = false;

    public LabelContent {
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(barcode, "barcode");
    }

    /** The label for a product (which must have a barcode) with both prices, VAT added on VATable items. */
    public static LabelContent of(ProductInfo p) {
        return of(p, true, true);
    }

    /**
     * The label with only the prices the user ticked. The wholesale price is left off anyway when
     * the product has none.
     */
    public static LabelContent of(ProductInfo p, boolean showRetail, boolean showWholesale) {
        return new LabelContent(p.name(), p.barcode(),
                showRetail ? shelfPrice(p.retailPrice(), p.vatable()) : null,
                showWholesale && p.wholesalePrice() != null ? shelfPrice(p.wholesalePrice(), p.vatable()) : null);
    }

    /** The price before VAT plus 12% VAT (rounded half-up like POS), when the item is VATable. */
    static BigDecimal shelfPrice(BigDecimal beforeVat, boolean vatable) {
        if (!SHOW_PRICES_WITH_VAT || !vatable) {
            return Money.round(beforeVat);
        }
        return Money.round(beforeVat.add(Money.round(beforeVat.multiply(VAT_RATE))));
    }
}
