package pos.contract;

import shared.Money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One payment towards a completed sale (Payment in the class diagram).
 *
 * Rules, checked here so a wrong payment can never be saved or synced:
 * - no amount is negative, and change = amountTendered - amountApplied;
 * - CASH: no reference number; the customer may hand over more (change);
 * - GCASH / MAYA / QRPH: a reference number is required, and the amount
 *   tendered is exactly the amount applied (no change).
 *
 * @param paymentNo      1, 2 ... (one payment per sale for now)
 * @param referenceNo    e-wallet / QRPh reference number typed by the cashier;
 *                       null for CASH. Trimmed; at most 50 characters.
 * @param amountTendered what the customer handed over (e.g. 500.00)
 * @param amountApplied  the part used to pay the sale (e.g. 349.75)
 * @param change         amountTendered - amountApplied (e.g. 150.25)
 */
public record PaymentRecord(
        int paymentNo,
        PaymentMethod method,
        String referenceNo,
        BigDecimal amountTendered,
        BigDecimal amountApplied,
        BigDecimal change) {

    /** Longest reference number the Payment table stores. */
    public static final int REFERENCE_MAX_LENGTH = 50;

    public PaymentRecord {
        Objects.requireNonNull(method, "method");
        if (paymentNo < 1) {
            throw new IllegalArgumentException("paymentNo starts at 1");
        }
        amountTendered = Money.round(Objects.requireNonNull(amountTendered, "amountTendered"));
        amountApplied = Money.round(Objects.requireNonNull(amountApplied, "amountApplied"));
        change = Money.round(Objects.requireNonNull(change, "change"));
        if (Money.isNegative(amountTendered) || Money.isNegative(amountApplied) || Money.isNegative(change)) {
            throw new IllegalArgumentException("Payment amounts cannot be negative");
        }
        if (amountTendered.subtract(amountApplied).compareTo(change) != 0) {
            throw new IllegalArgumentException("change must equal amountTendered - amountApplied");
        }

        referenceNo = (referenceNo == null || referenceNo.isBlank()) ? null : referenceNo.trim();
        if (method.needsReference() && referenceNo == null) {
            throw new IllegalArgumentException("A " + method + " payment needs its reference number");
        }
        if (!method.needsReference() && referenceNo != null) {
            throw new IllegalArgumentException("A " + method + " payment has no reference number");
        }
        if (referenceNo != null && referenceNo.length() > REFERENCE_MAX_LENGTH) {
            throw new IllegalArgumentException("Reference number is longer than " + REFERENCE_MAX_LENGTH);
        }
        if (!method.givesChange() && change.signum() != 0) {
            throw new IllegalArgumentException("A " + method + " payment gives no change");
        }
    }
}
