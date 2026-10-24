package shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Shared money rules for every module (POS, Product & Inventory, Sync).
 *
 * - Amounts are BigDecimal, never double (0.1 + 0.2 != 0.3 in double).
 * - Two decimal places, rounded half-up (1.005 -> 1.01).
 * - Stored in MySQL as DECIMAL(12,2).
 * - Prices are BEFORE VAT; POS adds 12% VAT on VATable items (pos.cart.SaleTotals).
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() { }

    /** Parses an amount such as "149.50". */
    public static BigDecimal of(String amount) {
        return round(new BigDecimal(amount.trim()));
    }

    /** Rounds to 2 decimal places, half-up. */
    public static BigDecimal round(BigDecimal amount) {
        return amount.setScale(SCALE, ROUNDING);
    }

    /** "1,234.50" — add the currency sign where it is shown. */
    public static String format(BigDecimal amount) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(round(amount));
    }

    /** True when the amount is less than zero. */
    public static boolean isNegative(BigDecimal amount) {
        return amount.signum() < 0;
    }
}
