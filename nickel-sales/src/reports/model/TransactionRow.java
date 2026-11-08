package reports.model;

import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.ReturnRecord;
import pos.contract.SaleRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One row of the Transactions tab: a completed sale, or a RETURN (POS Void Item / Void Transaction)
 * of one. A return row has the return number, when / where / by whom it was voided, the refund method,
 * the number of lines returned and a MINUS total; {@link #sale} is then the sale it was voided from.
 *
 * @param ret null for a sale's own row
 */
public record TransactionRow(SaleRecord sale, ReturnRecord ret) {

    public TransactionRow {
        Objects.requireNonNull(sale, "sale");
        if (ret != null && !ret.saleId().equals(sale.saleId())) {
            throw new IllegalArgumentException("The return " + ret.returnNo() + " is not of sale " + sale.invoiceNo());
        }
    }

    public static TransactionRow of(SaleRecord sale) {
        return new TransactionRow(sale, null);
    }

    public boolean isReturn() {
        return ret != null;
    }

    /** The invoice number, or the return number (invoice number + "-01" ...) for a return. */
    public String number() {
        return isReturn() ? ret.returnNo() : sale.invoiceNo();
    }

    public Instant when() {
        return isReturn() ? ret.returnedAt() : sale.completedAt();
    }

    /** Who rang up the sale, or who voided it. */
    public String cashierId() {
        return isReturn() ? ret.cashierUserId() : sale.cashierUserId();
    }

    public String terminalId() {
        return isReturn() ? ret.terminalId() : sale.terminalId();
    }

    /** How it was paid, or how the refund went back. */
    public List<PaymentMethod> methods() {
        return isReturn() ? List.of(ret.refundMethod())
                : sale.payments().stream().map(PaymentRecord::method).distinct().toList();
    }

    /** Lines sold, or lines returned. */
    public int items() {
        return isReturn() ? ret.lines().size() : sale.lines().size();
    }

    /** The sale's total, or MINUS the amount refunded. */
    public BigDecimal total() {
        return isReturn() ? ret.total().negate() : sale.total();
    }
}
