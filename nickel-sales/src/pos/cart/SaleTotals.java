package pos.cart;

import shared.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * The money totals of a sale, with the VAT breakdown a receipt shows.
 * Shelf prices INCLUDE VAT, so the customer pays exactly the sum of the line totals:
 * - total          = sum of all line totals (what the customer pays)
 * - vatableSales   = the VATable lines' total without its VAT (total / 1.12, rounded once)
 * - vatAmount      = the VAT inside the VATable lines (their total - vatableSales)
 * - vatExemptSales = sum of the line totals of VAT-exempt items
 * so vatableSales + vatAmount + vatExemptSales = total. These are the same numbers a SaleRecord stores.
 */
public record SaleTotals(BigDecimal vatableSales, BigDecimal vatExemptSales, BigDecimal vatAmount,
                         BigDecimal total) {

    public static final SaleTotals ZERO = new SaleTotals(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

    /** Works out the totals of {@code lines}. */
    public static SaleTotals of(List<SaleLineItem> lines) {
        BigDecimal vatableWithVat = Money.ZERO;
        BigDecimal exempt = Money.ZERO;
        for (SaleLineItem line : lines) {
            if (line.vatable()) {
                vatableWithVat = vatableWithVat.add(line.lineTotal());
            } else {
                exempt = exempt.add(line.lineTotal());
            }
        }
        BigDecimal vatable = Vat.netOf(vatableWithVat);
        BigDecimal vat = vatableWithVat.subtract(vatable);
        return new SaleTotals(vatable, exempt, vat, vatableWithVat.add(exempt));
    }

    /** Sum of the line totals, VAT included (the same as {@link #total()}: nothing is added on top). */
    public BigDecimal subtotal() {
        return total;
    }
}
