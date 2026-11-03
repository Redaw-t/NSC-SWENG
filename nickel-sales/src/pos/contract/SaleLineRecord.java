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
 * @param unitPrice   price charged per unit, before VAT
 * @param priceType   RETAIL or WHOLESALE
 * @param vatable     copied from the product; false = VAT-exempt line
 * @param lineTotal   quantity x unitPrice, rounded to 2 decimals (checked)
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
        BigDecimal lineTotal) {

    public SaleLineRecord {
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

    /** quantity x unitPrice, rounded to 2 decimals: the one way a line total is worked out. */
    public static BigDecimal totalOf(BigDecimal quantity, BigDecimal unitPrice) {
        return Money.round(quantity.multiply(unitPrice));
    }
}
