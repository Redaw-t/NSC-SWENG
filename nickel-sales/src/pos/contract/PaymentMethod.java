package pos.contract;

/**
 * How a payment was made (PaymentMethod in the class diagram). Stored as text
 * in Payment.Method, so methods can be added later without changing the table.
 *
 * GCash, Maya and QRPh are NOT processed by the system: the customer pays on
 * their phone, the cashier checks it went through and types the reference
 * number from the confirmation (requirements FR-04, CON-05).
 */
public enum PaymentMethod {
    CASH,
    GCASH,
    MAYA,
    QRPH;

    /** True for e-wallet / QR payments, which must record a reference number. */
    public boolean needsReference() {
        return this != CASH;
    }

    /** True when the customer may hand over more than is due and get change. */
    public boolean givesChange() {
        return this == CASH;
    }
}
