package pos.cart;

import shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Philippine VAT. Shelf prices INCLUDE VAT: a VATable item priced 15.00 is 13.39 of sales plus
 * 1.61 VAT. The VAT is taken out once for the whole sale (see {@link SaleTotals}), never added on top.
 */
public final class Vat {

    /** 12% VAT. */
    public static final BigDecimal RATE = new BigDecimal("0.12");
    private static final BigDecimal ONE_PLUS_RATE = BigDecimal.ONE.add(RATE);   // 1.12

    private Vat() { }

    /** The VATable sales inside a VAT-inclusive amount, rounded half-up: 15.00 -> 13.39, 112.00 -> 100.00. */
    public static BigDecimal netOf(BigDecimal withVat) {
        return Money.round(withVat.divide(ONE_PLUS_RATE, 10, RoundingMode.HALF_UP));
    }

    /** The VAT inside a VAT-inclusive amount: 15.00 -> 1.61, 112.00 -> 12.00. Always withVat - netOf(withVat). */
    public static BigDecimal inside(BigDecimal withVat) {
        return Money.round(withVat).subtract(netOf(withVat));
    }
}
