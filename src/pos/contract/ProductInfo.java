package pos.contract;

import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * CONTRACT — what POS needs to know about a product, supplied by the
 * Product &amp; Inventory module through {@link ProductLookup}.
 *
 * This is a read-only view, not the product entity: Product &amp; Inventory
 * owns its own tables and classes and only has to fill these fields.
 *
 * @param productId      the product's unique ID
 * @param barcode        the code a scanner reads; null if the product has none
 * @param name           shown on screen and printed on the receipt
 * @param unit           e.g. "pc", "box", "kg"
 * @param retailPrice    price per unit BEFORE VAT (POS adds 12% on VATable items)
 * @param wholesalePrice price per unit before VAT when a sale line reaches the
 *                       wholesale quantity (see POS pricing); null = no wholesale price
 * @param vatable        true for almost every product; false = VAT-exempt item
 * @param active         false = discontinued; POS will not sell it
 * @param stockOnHand    current stock; POS refuses to sell more than this
 */
public record ProductInfo(
        String productId,
        String barcode,
        String name,
        String unit,
        BigDecimal retailPrice,
        BigDecimal wholesalePrice,
        boolean vatable,
        boolean active,
        BigDecimal stockOnHand) {

    public ProductInfo {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(retailPrice, "retailPrice");
        Objects.requireNonNull(stockOnHand, "stockOnHand");
        if (Money.isNegative(retailPrice) || (wholesalePrice != null && Money.isNegative(wholesalePrice))) {
            throw new IllegalArgumentException("Prices cannot be negative: " + productId);
        }
        retailPrice = Money.round(retailPrice);
        wholesalePrice = wholesalePrice == null ? null : Money.round(wholesalePrice);
        stockOnHand = Quantity.normalize(stockOnHand);
    }

    /** True when the product has a wholesale price at all. */
    public boolean hasWholesalePrice() {
        return wholesalePrice != null;
    }
}
