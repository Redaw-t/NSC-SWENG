package pos.contract;

import shared.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * CONTRACT — a COMPLETED sale (Sale in the class diagram, status COMPLETED).
 * Built by POS; read by Sync ({@link SaleExport}) and by Reports ({@link SaleHistory}).
 * Inventory is handed plain stock lines instead (inventory.contract.StockRecorder).
 *
 * A completed sale never changes. Only completed sales are saved: a sale that
 * is cancelled before payment is simply never stored.
 *
 * Totals. Prices are BEFORE VAT; 12% VAT is added on the VATable items:
 * - vatableSales   = sum of line totals of VATable lines
 * - vatExemptSales = sum of line totals of VAT-exempt lines
 * - vatAmount      = 12% of vatableSales, rounded once for the whole sale
 * - total          = vatableSales + vatExemptSales + vatAmount = what the customer pays
 * so the line totals add up to {@link #subtotal()} and total = subtotal + VAT.
 *
 * (Sales saved before this rule, when shelf prices already included VAT, have
 * line totals that add up to the total instead; they still load and print
 * exactly as they were sold.)
 *
 * The constructor checks that the lines are numbered 1, 2, 3 ..., that the
 * totals add up as above, and that the payments cover exactly the total, so
 * every module can trust a SaleRecord it is given.
 *
 * @param saleId        UUID; unique across every terminal (Sync relies on this)
 * @param invoiceNo     e.g. "T01-2026-000123": terminal, year (PH time), running number
 * @param terminalId    the terminal that made the sale, e.g. "T01"
 * @param cashierUserId the logged-in user's ID, e.g. "NSC-00002"
 * @param completedAt   when payment was completed (UTC; shown in PH time, see PhTime)
 * @param payments      at least one; their amountApplied add up to total
 */
public record SaleRecord(
        String saleId,
        String invoiceNo,
        String terminalId,
        String cashierUserId,
        Instant completedAt,
        List<SaleLineRecord> lines,
        List<PaymentRecord> payments,
        BigDecimal vatableSales,
        BigDecimal vatExemptSales,
        BigDecimal vatAmount,
        BigDecimal total) {

    public SaleRecord {
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(invoiceNo, "invoiceNo");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(cashierUserId, "cashierUserId");
        Objects.requireNonNull(completedAt, "completedAt");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        payments = List.copyOf(Objects.requireNonNull(payments, "payments"));
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A sale needs at least one line");
        }
        if (payments.isEmpty()) {
            throw new IllegalArgumentException("A completed sale needs at least one payment");
        }
        vatableSales = Money.round(Objects.requireNonNull(vatableSales, "vatableSales"));
        vatExemptSales = Money.round(Objects.requireNonNull(vatExemptSales, "vatExemptSales"));
        vatAmount = Money.round(Objects.requireNonNull(vatAmount, "vatAmount"));
        total = Money.round(Objects.requireNonNull(total, "total"));

        BigDecimal lineSum = Money.ZERO;
        BigDecimal exemptSum = Money.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            SaleLineRecord line = lines.get(i);
            if (line.lineNo() != i + 1) {
                throw new IllegalArgumentException("Lines must be numbered 1, 2, 3 ... in order");
            }
            lineSum = lineSum.add(line.lineTotal());
            if (!line.vatable()) {
                exemptSum = exemptSum.add(line.lineTotal());
            }
        }
        if (vatExemptSales.compareTo(exemptSum) != 0) {
            throw new IllegalArgumentException("vatExemptSales must be the sum of the VAT-exempt lines");
        }
        if (Money.isNegative(vatableSales) || Money.isNegative(vatAmount)
                || vatableSales.add(vatAmount).add(vatExemptSales).compareTo(total) != 0) {
            throw new IllegalArgumentException("vatableSales + vatAmount + vatExemptSales must equal total");
        }
        boolean vatAdded = lineSum.add(vatAmount).compareTo(total) == 0
                && vatableSales.compareTo(lineSum.subtract(exemptSum)) == 0;
        boolean vatInsidePrices = lineSum.compareTo(total) == 0;   // sales saved under the old rule
        if (!vatAdded && !vatInsidePrices) {
            throw new IllegalArgumentException("the line totals plus VAT must equal total");
        }

        BigDecimal applied = Money.ZERO;
        for (int i = 0; i < payments.size(); i++) {
            PaymentRecord p = payments.get(i);
            if (p.paymentNo() != i + 1) {
                throw new IllegalArgumentException("Payments must be numbered 1, 2, 3 ... in order");
            }
            applied = applied.add(p.amountApplied());
        }
        if (applied.compareTo(total) != 0) {
            throw new IllegalArgumentException("The payments must cover exactly the total");
        }
    }

    /** Sum of the line totals (before VAT on sales made under the current rule). */
    public BigDecimal subtotal() {
        BigDecimal sum = Money.ZERO;
        for (SaleLineRecord line : lines) {
            sum = sum.add(line.lineTotal());
        }
        return sum;
    }

}
