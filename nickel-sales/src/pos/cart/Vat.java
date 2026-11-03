package pos.cart;

import shared.Money;

import java.math.BigDecimal;

/**
 * Philippine VAT. Prices are BEFORE VAT: the VAT is added on top of the
 * VATable items, once for the whole sale (see {@link SaleTotals}).
 */
public final class Vat {

    /** 12% VAT. */
    public static final BigDecimal RATE = new BigDecimal("0.12");

    private Vat() { }

    /** The VAT on an amount before VAT, rounded half-up: 100.00 -> 12.00, 223.21 -> 26.79. */
    public static BigDecimal on(BigDecimal beforeVat) {
        return Money.round(beforeVat.multiply(RATE));
    }
}
