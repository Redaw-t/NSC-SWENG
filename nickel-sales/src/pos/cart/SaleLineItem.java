package pos.cart;

import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of the sale being rung up (SaleLineItem in the class diagram).
 * One line per product: scanning the same product again raises its quantity.
 * Built by {@link PricingService}; replaced (not changed) when the quantity
 * changes, so the price type always matches the quantity.
 *
 * @param barcode   null if the product has none
 * @param quantity  more than 0, up to 3 decimals
 * @param unitPrice price per unit, before VAT
 * @param vatable   false = VAT-exempt product
 */
public record SaleLineItem(
        String productId,
        String barcode,
        String productName,
        String unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        PriceType priceType,
        boolean vatable) {

    public SaleLineItem {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(priceType, "priceType");
        quantity = Quantity.normalize(Objects.requireNonNull(quantity, "quantity"));
        unitPrice = Money.round(Objects.requireNonNull(unitPrice, "unitPrice"));
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity must be more than 0");
        }
    }

    /** quantity x unitPrice, rounded to 2 decimals. */
    public BigDecimal lineTotal() {
        return SaleLineRecord.totalOf(quantity, unitPrice);
    }

    /** This line as it is saved with a completed sale (used at checkout). */
    public SaleLineRecord toRecord(int lineNo) {
        return new SaleLineRecord(lineNo, productId, barcode, productName, unit, quantity, unitPrice,
                priceType, vatable, lineTotal());
    }
}
