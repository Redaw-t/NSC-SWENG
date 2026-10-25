package pos.checkout;

import pos.cart.Sale;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import shared.Money;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Takes the customer's payment for the open sale (PaymentService in the class
 * diagram). It only builds the payment; nothing is saved until
 * SalesService.finalizeSale. The cashier can change the method or amount and
 * call this again as often as needed before finalizing.
 *
 * - CASH: the cash received must cover the total; the change is worked out.
 * - GCASH / MAYA / QRPH: the system does not process the payment. The
 *   customer pays on their phone, the cashier checks it went through and
 *   types the reference number from the confirmation (requirement FR-04).
 *   The amount is exactly the total; there is no change.
 */
public class PaymentService {

    /** Letters, digits, spaces and dashes; 4 to 50 characters. */
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 -]{2,48}[A-Za-z0-9]");

    /**
     * The payment for {@code sale}'s current total.
     *
     * @param tendered    cash received (CASH only; ignored for the others, may be null)
     * @param referenceNo the e-wallet / QRPh reference number (ignored for CASH)
     * @throws CheckoutException with a message for the cashier
     */
    public PaymentRecord recordPayment(Sale sale, PaymentMethod method, BigDecimal tendered, String referenceNo)
            throws CheckoutException {
        Objects.requireNonNull(sale, "sale");
        Objects.requireNonNull(method, "method");
        BigDecimal total = sale.total();
        if (sale.isEmpty() || total.signum() <= 0) {
            throw new CheckoutException("Add an item before taking payment.");
        }
        if (method == PaymentMethod.CASH) {
            if (tendered == null) {
                throw new CheckoutException("Enter the cash received.");
            }
            BigDecimal cash = Money.round(tendered);
            if (cash.compareTo(total) < 0) {
                throw new CheckoutException("Cash received (" + Money.format(cash)
                        + ") is less than the total (" + Money.format(total) + ").");
            }
            return new PaymentRecord(1, method, null, cash, total, computeChange(total, cash));
        }
        if (referenceNo == null || referenceNo.isBlank()) {
            throw new CheckoutException("Enter the " + label(method) + " reference number.");
        }
        if (!validateReference(method, referenceNo)) {
            throw new CheckoutException("That " + label(method) + " reference number doesn't look right. "
                    + "Use only letters, numbers, spaces and dashes (4 to 50 characters).");
        }
        return new PaymentRecord(1, method, referenceNo.trim(), total, total, Money.ZERO);
    }

    /** Change for cash: tendered - total (never negative here; recordPayment checks first). */
    public BigDecimal computeChange(BigDecimal total, BigDecimal tendered) {
        return Money.round(tendered).subtract(Money.round(total));
    }

    /**
     * True when {@code referenceNo} is acceptable for {@code method}: CASH takes
     * none; the others need 4-50 letters, digits, spaces or dashes.
     */
    public boolean validateReference(PaymentMethod method, String referenceNo) {
        if (!method.needsReference()) {
            return referenceNo == null || referenceNo.isBlank();
        }
        return referenceNo != null && REFERENCE.matcher(referenceNo.trim()).matches();
    }

    /** How the method is written for people: "GCash", "Maya", "QRPh", "Cash". */
    public static String label(PaymentMethod method) {
        return switch (method) {
            case CASH -> "Cash";
            case GCASH -> "GCash";
            case MAYA -> "Maya";
            case QRPH -> "QRPh";
        };
    }
}
