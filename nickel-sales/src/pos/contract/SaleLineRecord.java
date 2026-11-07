package pos.contract;

import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of a completed sale (SaleLineItem in the class diagram). Name,
 * unit and price are COPIES taken at the moment of sale, so later product or
 * price changes never alter past sales.
 *
 * @param lineNo      1, 2, 3 ... in the order shown on the receipt
 * @param barcode     copied from the product; null if it had none
 * @param quantity    more than 0, up to 3 decimals (see Quantity)
 * @param unitPrice   price charged per unit, VAT included
 * @param priceType   RETAIL or WHOLESALE
 * @param vatable     copied from the product; false = VAT-exempt line
 * @param lineTotal   quantity x unitPrice, rounded to 2 decimals (checked)
 * @param originalPrice null = the price was not edited; else the unit price before the cashier
 *                    changed it with F4 Edit Price (VAT included). Saved as SaleLine.OriginalPrice.
 */
public record SaleLineRecord(
        int lineNo,
        String productId,
        String barcode,
        String productName,
        String unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        PriceType priceType,
        boolean vatable,
        BigDecimal lineTotal,
        BigDecimal originalPrice) {

    /** A line whose price was not edited. */
    public SaleLineRecord(int lineNo, String productId, String barcode, String productName, String unit,
                          BigDecimal quantity, BigDecimal unitPrice, PriceType priceType, boolean vatable,
                          BigDecimal lineTotal) {
        this(lineNo, productId, barcode, productName, unit, quantity, unitPrice, priceType, vatable, lineTotal, null);
    }

    /** True when the cashier changed this line's price (F4); {@link #originalPrice} has the old one. */
    public boolean priceEdited() {
        return originalPrice != null;
    }

    public SaleLineRecord {
        if (originalPrice != null) {
            originalPrice = Money.round(originalPrice);
            if (Money.isNegative(originalPrice)) {
                throw new IllegalArgumentException("Original price cannot be negative (line " + lineNo + ")");
            }
        }
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(priceType, "priceType");
        if (lineNo < 1) {
            throw new IllegalArgumentException("lineNo starts at 1");
        }
        quantity = Quantity.normalize(Objects.requireNonNull(quantity, "quantity"));
        unitPrice = Money.round(Objects.requireNonNull(unitPrice, "unitPrice"));
        lineTotal = Money.round(Objects.requireNonNull(lineTotal, "lineTotal"));
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity must be more than 0 (line " + lineNo + ")");
        }
        if (Money.isNegative(unitPrice)) {
            throw new IllegalArgumentException("Unit price cannot be negative (line " + lineNo + ")");
        }
        if (lineTotal.compareTo(totalOf(quantity, unitPrice)) != 0) {
            throw new IllegalArgumentException("lineTotal must be quantity x unitPrice (line " + lineNo + ")");
        }
    }

    /**
     * Product IDs of open items start with this. An open item is typed in at the till (F2) for
     * something the store sells that is not in the catalog: it has no Product row, is never added
     * to the Product table, and moves no stock. Catalog IDs look like "P-0001", so they never clash.
     */
    public static final String OPEN_ITEM_PREFIX = "OPEN-";

    /** True for an open item (not a catalog product; see {@link #OPEN_ITEM_PREFIX}). */
    public static boolean isOpenItem(String productId) {
        return productId != null && productId.startsWith(OPEN_ITEM_PREFIX);
    }

    /** True when this line is an open item, not a catalog product. */
    public boolean openItem() {
        return isOpenItem(productId);
    }

    /** quantity x unitPrice, rounded to 2 decimals: the one way a line total is worked out. */
    public static BigDecimal totalOf(BigDecimal quantity, BigDecimal unitPrice) {
        return Money.round(quantity.multiply(unitPrice));
    }
}
