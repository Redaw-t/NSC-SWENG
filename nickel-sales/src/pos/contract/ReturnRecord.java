package pos.contract;

import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * CONTRACT — one void of items from a completed sale (tables Return and ReturnLine), made by POS
 * Void Item / Void Transaction. The sale itself never changes; its invoice lists its returns below
 * the sold items.
 *
 * Amounts are POSITIVE (what was refunded); receipts print them with a minus sign. Like a sale:
 * vatableSales + vatAmount + vatExemptSales = total, and total = the sum of the line totals.
 *
 * @param returnNo      the sale's invoice number + "-01", "-02" ..., e.g. "T01-2026-000123-01" (see {@link #numberFor})
 * @param terminalId    the terminal the void was done on
 * @param cashierUserId who did the void
 * @param fullVoid      true when nothing is left on the sale after this return (it voids the rest of it)
 * @param refundMethod  how the money went back, chosen by the cashier in the refund card (may differ
 *                      from how the sale was paid)
 * @param refundReference the GCash / Maya / QRPh reference of the refund transfer; null for CASH
 */
public record ReturnRecord(
        String returnId,
        String returnNo,
        String saleId,
        String invoiceNo,
        String terminalId,
        String cashierUserId,
        Instant returnedAt,
        boolean fullVoid,
        PaymentMethod refundMethod,
        String refundReference,
        List<ReturnLineRecord> lines,
        BigDecimal vatableSales,
        BigDecimal vatExemptSales,
        BigDecimal vatAmount,
        BigDecimal total) {

    /**
     * A return number: an invoice number (terminal-year-number) plus "-01", "-02" ...
     * An invoice number alone has one dash-group fewer, so the two can't be mixed up.
     */
    private static final java.util.regex.Pattern RETURN_NO =
            java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,8}-\\d{4}-\\d{6,}-\\d{2,}$");

    public ReturnRecord {
        Objects.requireNonNull(returnId, "returnId");
        Objects.requireNonNull(returnNo, "returnNo");
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(invoiceNo, "invoiceNo");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(cashierUserId, "cashierUserId");
        Objects.requireNonNull(returnedAt, "returnedAt");
        Objects.requireNonNull(refundMethod, "refundMethod");
        refundReference = (refundReference == null || refundReference.isBlank()) ? null : refundReference.trim();
        // A GCash / Maya / QRPh refund's reference is required by the refund card (PaymentService.recordRefund),
        // not here: returns saved before the refund card existed have none, and must still load.
        if (!refundMethod.needsReference() && refundReference != null) {
            throw new IllegalArgumentException("A " + refundMethod + " refund has no reference number");
        }
        if (refundReference != null && refundReference.length() > PaymentRecord.REFERENCE_MAX_LENGTH) {
            throw new IllegalArgumentException("Refund reference is longer than " + PaymentRecord.REFERENCE_MAX_LENGTH);
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A return needs at least one line");
        }
        vatableSales = Money.round(Objects.requireNonNull(vatableSales, "vatableSales"));
        vatExemptSales = Money.round(Objects.requireNonNull(vatExemptSales, "vatExemptSales"));
        vatAmount = Money.round(Objects.requireNonNull(vatAmount, "vatAmount"));
        total = Money.round(Objects.requireNonNull(total, "total"));

        BigDecimal sum = Money.ZERO;
        BigDecimal exempt = Money.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            ReturnLineRecord line = lines.get(i);
            if (line.lineNo() != i + 1) {
                throw new IllegalArgumentException("Return lines must be numbered 1, 2, 3 ... in order");
            }
            sum = sum.add(line.lineTotal());
            if (!line.vatable()) {
                exempt = exempt.add(line.lineTotal());
            }
        }
        if (sum.compareTo(total) != 0) {
            throw new IllegalArgumentException("A return's total must be the sum of its lines");
        }
        if (exempt.compareTo(vatExemptSales) != 0) {
            throw new IllegalArgumentException("vatExemptSales must be the sum of the VAT-exempt lines");
        }
        if (Money.isNegative(vatableSales) || Money.isNegative(vatAmount)
                || vatableSales.add(vatAmount).add(vatExemptSales).compareTo(total) != 0) {
            throw new IllegalArgumentException("vatableSales + vatAmount + vatExemptSales must equal total");
        }
    }

    /**
     * The return number as printed in the RETURN slip's barcode: without dashes, "T01202600000101".
     * Its 14-digit tail packs two digits per Code 128 symbol, so the barcode is short enough for the
     * same 2-dot bars as the invoice barcode on 58 mm paper (the text under it keeps the dashes).
     */
    public static String barcodeValue(String returnNo) {
        return returnNo.replace("-", "");
    }

    /**
     * The scanned return barcode without dashes: a terminal ID (starts with a letter, no dashes)
     * then 4 + 6 + 2 digits (year, invoice number, return count), e.g. "T01202600000101".
     */
    private static final java.util.regex.Pattern BARCODE_VALUE =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,7}\\d{12}$");

    /** True when {@code text} looks like a return number ("T01-2026-000123-01"), not an invoice number. */
    public static boolean isReturnNo(String text) {
        return text != null && RETURN_NO.matcher(text.trim()).matches();
    }

    /**
     * True when {@code text} may be a return slip's barcode ("T01202600000101", see {@link #barcodeValue});
     * only a lookup can tell for sure, since the dashes are gone.
     */
    public static boolean mayBeReturnBarcode(String text) {
        return text != null && BARCODE_VALUE.matcher(text.trim()).matches();
    }

    /** The {@code n}th return of a sale: "T01-2026-000123-01". */
    public static String numberFor(String invoiceNo, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Return numbers start at 1");
        }
        return String.format("%s-%02d", invoiceNo, n);
    }

    /** Total quantity voided (for "Item Returned" on the receipt). */
    public BigDecimal pieces() {
        BigDecimal sum = BigDecimal.ZERO;
        for (ReturnLineRecord line : lines) {
            sum = sum.add(line.quantity());
        }
        return Quantity.normalize(sum);
    }
}
