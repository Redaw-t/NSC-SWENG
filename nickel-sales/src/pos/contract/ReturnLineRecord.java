package pos.contract;

import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One voided item of a {@link ReturnRecord} (table ReturnLine). Name, unit and price are copied
 * from the sale line it came from, so the return always matches what was sold.
 *
 * @param lineNo     1, 2, 3 ... within the return
 * @param saleLineNo the line of the sale this was voided from (SaleLine.LineNo)
 * @param quantity   how much was voided, more than 0 (positive)
 * @param unitPrice  the price it was sold at, VAT included
 * @param lineTotal  refunded for this line (positive). Normally quantity x unitPrice; when the last
 *                   of a line is voided it is whatever of the sale line's total was still left, so a
 *                   line voided in parts refunds exactly what was paid for it.
 */
public record ReturnLineRecord(
        int lineNo,
        int saleLineNo,
        String productId,
        String barcode,
        String productName,
        String unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        PriceType priceType,
        boolean vatable,
        BigDecimal lineTotal) {

    public ReturnLineRecord {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(priceType, "priceType");
        if (lineNo < 1 || saleLineNo < 1) {
            throw new IllegalArgumentException("Line numbers start at 1");
        }
        quantity = Quantity.normalize(Objects.requireNonNull(quantity, "quantity"));
        unitPrice = Money.round(Objects.requireNonNull(unitPrice, "unitPrice"));
        lineTotal = Money.round(Objects.requireNonNull(lineTotal, "lineTotal"));
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("Voided quantity must be more than 0 (line " + lineNo + ")");
        }
        if (Money.isNegative(unitPrice) || Money.isNegative(lineTotal)) {
            throw new IllegalArgumentException("Return amounts cannot be negative (line " + lineNo + ")");
        }
    }

    /** True when this line is an open item (no stock goes back). */
    public boolean openItem() {
        return SaleLineRecord.isOpenItem(productId);
    }
}
