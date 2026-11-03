package pos.cart;

import shared.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * The money totals of a sale, with the VAT breakdown a receipt shows.
 * Prices are BEFORE VAT, and 12% VAT is added on the VATable items:
 * - vatableSales   = sum of the line totals of VATable items
 * - vatExemptSales = sum of the line totals of VAT-exempt items
 * - vatAmount      = 12% of vatableSales, rounded once for the whole sale
 * - total          = vatableSales + vatExemptSales + vatAmount  (what the customer pays)
 * These are the same numbers a SaleRecord stores.
 */
public record SaleTotals(BigDecimal vatableSales, BigDecimal vatExemptSales, BigDecimal vatAmount,
                         BigDecimal total) {

    public static final SaleTotals ZERO = new SaleTotals(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

    /** Works out the totals of {@code lines}. */
    public static SaleTotals of(List<SaleLineItem> lines) {
        BigDecimal vatable = Money.ZERO;
        BigDecimal exempt = Money.ZERO;
        for (SaleLineItem line : lines) {
            if (line.vatable()) {
                vatable = vatable.add(line.lineTotal());
            } else {
                exempt = exempt.add(line.lineTotal());
            }
        }
        BigDecimal vat = Vat.on(vatable);
        return new SaleTotals(vatable, exempt, vat, vatable.add(exempt).add(vat));
    }

    /** Sum of the line totals, before VAT. */
    public BigDecimal subtotal() {
        return vatableSales.add(vatExemptSales);
    }
}
