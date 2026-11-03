package shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared quantity rules. Quantities are decimals so items sold by weight or
 * length (1.5 kg, 2.25 m) work as well as pieces.
 *
 * - BigDecimal with up to 3 decimal places; stored as DECIMAL(12,3).
 * - Shown without trailing zeros: 3 pieces display as "3", not "3.000".
 */
public final class Quantity {

    public static final int SCALE = 3;
    public static final BigDecimal ONE = new BigDecimal("1.000");

    private Quantity() { }

    /** Parses a quantity such as "2" or "1.5". */
    public static BigDecimal of(String quantity) {
        return normalize(new BigDecimal(quantity.trim()));
    }

    /** Rounds to 3 decimal places, half-up. */
    public static BigDecimal normalize(BigDecimal quantity) {
        return quantity.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** "3" for 3.000, "1.5" for 1.500. */
    public static String format(BigDecimal quantity) {
        BigDecimal stripped = normalize(quantity).stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}
