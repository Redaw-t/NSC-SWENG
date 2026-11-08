package reports.model;

import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.SaleRecord;

import java.util.Locale;
import java.util.Objects;

/**
 * What the Transaction History screen asks for. Only the period is required; every other
 * part is optional (null or blank = any).
 *
 * <p>The invoice number is special: an exact invoice number is found on ANY date (a customer
 * comes back days later with a receipt). If there is no exact match, the period's sales whose
 * invoice number contains the text are shown, so "000123" also works.
 *
 * @param cashierId  only sales rung up by this user ID, e.g. "NSC-00002"
 * @param terminalId only sales from this terminal, e.g. "T01"
 * @param method     only sales with a payment of this method
 * @param invoiceNo  all or part of an invoice number, e.g. "T01-2026-000123" or "000123"
 */
public record ReportFilter(ReportPeriod period, String cashierId, String terminalId,
                           PaymentMethod method, String invoiceNo) {

    public ReportFilter {
        Objects.requireNonNull(period, "period");
        cashierId = blankToNull(cashierId);
        terminalId = blankToNull(terminalId);
        invoiceNo = blankToNull(invoiceNo);
    }

    /** Every sale of the period. */
    public static ReportFilter of(ReportPeriod period) {
        return new ReportFilter(period, null, null, null, null);
    }

    /**
     * True if the sale has the chosen cashier, terminal and payment method. The period and the
     * invoice number are NOT checked here: TransactionHistoryService handles those, because an
     * exact invoice number ignores the period.
     */
    public boolean matches(SaleRecord sale) {
        if (cashierId != null && !cashierId.equalsIgnoreCase(sale.cashierUserId())) {
            return false;
        }
        if (terminalId != null && !terminalId.equalsIgnoreCase(sale.terminalId())) {
            return false;
        }
        if (method != null) {
            boolean paidThatWay = false;
            for (PaymentRecord p : sale.payments()) {
                paidThatWay |= p.method() == method;
            }
            return paidThatWay;
        }
        return true;
    }

    /** True if the sale's invoice number contains {@link #invoiceNo} (ignoring case), or no invoice number was given. */
    public boolean invoiceContains(SaleRecord sale) {
        return numberContains(sale.invoiceNo());
    }

    /** True if {@code number} (an invoice or return number) contains {@link #invoiceNo}, or none was given. */
    public boolean numberContains(String number) {
        return invoiceNo == null
                || number.toUpperCase(Locale.ROOT).contains(invoiceNo.toUpperCase(Locale.ROOT));
    }

    /**
     * {@link #matches(SaleRecord)} for a return row: the cashier and terminal are who / where it was
     * voided, the method is how the refund went back.
     */
    public boolean matches(TransactionRow row) {
        if (!row.isReturn()) {
            return matches(row.sale());
        }
        if (cashierId != null && !cashierId.equalsIgnoreCase(row.cashierId())) {
            return false;
        }
        if (terminalId != null && !terminalId.equalsIgnoreCase(row.terminalId())) {
            return false;
        }
        return method == null || row.methods().contains(method);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
