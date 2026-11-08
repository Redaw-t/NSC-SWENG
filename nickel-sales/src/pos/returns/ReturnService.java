package pos.returns;

import auth.contract.AuditLogger;
import inventory.contract.StockLine;
import inventory.contract.StockRecorder;
import pos.cart.Vat;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.ReturnHistory;
import pos.contract.ReturnLineRecord;
import pos.contract.ReturnRecord;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.repository.MySqlReturnRepository;
import shared.Database;
import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * POS Void Item / Void Transaction on a completed (retrieved) sale. The sale is never changed:
 * each void is saved as a {@link ReturnRecord} (tables Return, ReturnLine) numbered
 * &lt;invoice&gt;-01, -02 ..., and the voided catalog items go back into stock.
 *
 * Each void is ONE database transaction: the sale's row is locked (so two terminals can't void the
 * same item twice), what is still left of each line is worked out from the earlier voids, the
 * return is saved and the stock is put back. If anything fails, nothing is saved. After the
 * commit the void is logged (SALE_ITEM_VOIDED / SALE_VOIDED + the return number).
 *
 * How the money goes back is chosen by the cashier in the refund card (Cash, GCash, Maya, QRPh);
 * it may differ from how the sale was paid. The screen previews the void first (previewItem /
 * previewTransaction) to show the amount in that card, then saves it with the chosen refund.
 */
public class ReturnService {

    private final MySqlReturnRepository returns;
    private final StockRecorder stock;
    private final AuditLogger audit;
    private final String terminalId;

    public ReturnService(MySqlReturnRepository returns, StockRecorder stock, AuditLogger audit, String terminalId) {
        this.returns = Objects.requireNonNull(returns, "returns");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
    }

    /** Read-only: the voids of a sale. */
    public ReturnHistory history() {
        return returns;
    }

    /**
     * What is still left of each line of {@code sale} after {@code done} (sale line no. -> quantity;
     * 0 when the line is fully voided).
     */
    public static Map<Integer, BigDecimal> remaining(SaleRecord sale, List<ReturnRecord> done) {
        Map<Integer, BigDecimal> left = new LinkedHashMap<>();
        for (SaleLineRecord line : sale.lines()) {
            left.put(line.lineNo(), line.quantity());
        }
        for (ReturnRecord r : done) {
            for (ReturnLineRecord line : r.lines()) {
                left.computeIfPresent(line.saleLineNo(), (k, q) -> Quantity.normalize(q.subtract(line.quantity())));
            }
        }
        return left;
    }

    /**
     * Void Item: voids {@code quantity} of line {@code saleLineNo} of {@code sale}.
     *
     * @throws ReturnException nothing / not that much left to void (nothing was saved)
     * @throws RuntimeException database problem (nothing was saved; cause = SQLException)
     */
    public ReturnRecord voidItem(SaleRecord sale, int saleLineNo, BigDecimal quantity, String userId,
                                 PaymentRecord refund) throws ReturnException {
        return save(sale, itemRequest(saleLineNo, quantity), userId, refund);
    }

    /**
     * Void Transaction: voids everything still left on {@code sale}.
     *
     * @throws ReturnException everything was already voided (nothing was saved)
     * @throws RuntimeException database problem (nothing was saved; cause = SQLException)
     */
    public ReturnRecord voidTransaction(SaleRecord sale, String userId, PaymentRecord refund) throws ReturnException {
        return save(sale, null, userId, refund);
    }

    /**
     * The POS Refund: voids every line the cashier marked ({@code wanted}: sale line no. -> quantity)
     * as ONE return with one return number, refunded by {@code refund}.
     *
     * @throws ReturnException nothing marked, or more than is left (nothing was saved)
     * @throws RuntimeException database problem (nothing was saved; cause = SQLException)
     */
    public ReturnRecord voidLines(SaleRecord sale, Map<Integer, BigDecimal> wanted, String userId,
                                  PaymentRecord refund) throws ReturnException {
        return save(sale, request(wanted), userId, refund);
    }

    /** What {@link #voidLines} would refund, before the refund card asks how (nothing is saved). */
    public ReturnRecord preview(SaleRecord sale, List<ReturnRecord> earlier, Map<Integer, BigDecimal> wanted,
                                String userId) throws ReturnException {
        checkVoidable(sale);
        return build(sale, earlier, request(wanted), userId, Instant.now(), null);
    }

    private static Map<Integer, BigDecimal> request(Map<Integer, BigDecimal> wanted) throws ReturnException {
        if (wanted == null || wanted.isEmpty()) {
            throw new ReturnException("Mark an item to void first (Void Item or Void Transaction).");
        }
        Map<Integer, BigDecimal> clean = new LinkedHashMap<>();
        for (Map.Entry<Integer, BigDecimal> e : wanted.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() <= 0) {
                throw new ReturnException("Void quantities must be above 0.");
            }
            clean.put(e.getKey(), Quantity.normalize(e.getValue()));
        }
        return clean;
    }

    /**
     * What Void Item would refund, before the refund card asks how (nothing is saved).
     * {@code earlier} = the sale's voids so far.
     */
    public ReturnRecord previewItem(SaleRecord sale, List<ReturnRecord> earlier, int saleLineNo,
                                    BigDecimal quantity, String userId) throws ReturnException {
        checkVoidable(sale);
        return build(sale, earlier, itemRequest(saleLineNo, quantity), userId, Instant.now(), null);
    }

    /** What Void Transaction would refund (nothing is saved). */
    public ReturnRecord previewTransaction(SaleRecord sale, List<ReturnRecord> earlier, String userId)
            throws ReturnException {
        checkVoidable(sale);
        return build(sale, earlier, null, userId, Instant.now(), null);
    }

    private static Map<Integer, BigDecimal> itemRequest(int saleLineNo, BigDecimal quantity) throws ReturnException {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ReturnException("Type a quantity above 0.");
        }
        return Map.of(saleLineNo, Quantity.normalize(quantity));
    }

    private static void checkVoidable(SaleRecord sale) throws ReturnException {
        Objects.requireNonNull(sale, "sale");
        if (sale.subtotal().compareTo(sale.total()) != 0) {
            throw new ReturnException("This sale was made when VAT was added on top of prices; "
                    + "it can't be voided here.");
        }
    }

    /**
     * {@code wanted} = sale line no. -> quantity; null = everything left (Void Transaction).
     * {@code refund} = how the money goes back (from the refund card); its amount must be the
     * return's total, so a sale changed by another terminal meanwhile is not refunded wrongly.
     */
    private ReturnRecord save(SaleRecord sale, Map<Integer, BigDecimal> wanted, String userId,
                              PaymentRecord refund) throws ReturnException {
        Objects.requireNonNull(sale, "sale");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(refund, "refund");
        checkVoidable(sale);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ReturnRecord saved;
        try {
            saved = Database.inTransaction(tx -> {
                if (!returns.lockSale(tx, sale.saleId())) {
                    throw new ReturnException("Sale " + sale.invoiceNo() + " no longer exists.");
                }
                List<ReturnRecord> earlier = returns.findBySaleId(tx, sale.saleId());
                ReturnRecord record = build(sale, earlier, wanted, userId, now, refund);
                if (record.total().compareTo(refund.amountApplied()) != 0) {
                    throw new ReturnException("The refund for this void is now " + Money.format(record.total())
                            + " (the sale was changed on another terminal). Nothing was saved; try again.");
                }
                returns.save(record, tx);
                List<StockLine> back = record.lines().stream()
                        .filter(line -> !line.openItem())   // open items were never in stock
                        .map(line -> new StockLine(line.productId(), line.quantity()))
                        .toList();
                if (!back.isEmpty()) {
                    stock.recordReturn(record.returnNo(), back, tx);
                }
                return record;
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not save the void", e);
        }
        try {
            audit.log((saved.fullVoid() ? "SALE_VOIDED " : "SALE_ITEM_VOIDED ") + saved.returnNo(), userId);
        } catch (RuntimeException logFailure) {
            System.err.println("[AUDIT] could not log void " + saved.returnNo() + ": " + logFailure);
        }
        return saved;
    }

    /** {@link #build(SaleRecord, List, Map, String, Instant, PaymentRecord)} refunded in cash. */
    public ReturnRecord build(SaleRecord sale, List<ReturnRecord> earlier, Map<Integer, BigDecimal> wanted,
                              String userId, Instant now) throws ReturnException {
        return build(sale, earlier, wanted, userId, now, null);
    }

    /**
     * The return to save, checked against what is still left on the sale. No database (used by the
     * checks and the previews). {@code refund} null = cash (for a preview).
     */
    public ReturnRecord build(SaleRecord sale, List<ReturnRecord> earlier, Map<Integer, BigDecimal> wanted,
                              String userId, Instant now, PaymentRecord refund) throws ReturnException {
        Map<Integer, BigDecimal> left = remaining(sale, earlier);
        Map<Integer, BigDecimal> refundedSoFar = new HashMap<>();
        for (ReturnRecord r : earlier) {
            for (ReturnLineRecord line : r.lines()) {
                refundedSoFar.merge(line.saleLineNo(), line.lineTotal(), BigDecimal::add);
            }
        }
        if (wanted != null) {
            for (Integer no : wanted.keySet()) {
                if (!left.containsKey(no)) {
                    throw new ReturnException("Sale " + sale.invoiceNo() + " has no line " + no + ".");
                }
            }
        }

        List<ReturnLineRecord> lines = new ArrayList<>();
        BigDecimal vatableWithVat = Money.ZERO;
        BigDecimal exempt = Money.ZERO;
        for (SaleLineRecord line : sale.lines()) {
            BigDecimal stillLeft = left.get(line.lineNo());
            BigDecimal qty = wanted == null ? stillLeft : wanted.get(line.lineNo());
            if (qty == null || (wanted == null && qty.signum() <= 0)) {
                continue;
            }
            if (stillLeft.signum() <= 0) {
                throw new ReturnException(line.productName() + " was already voided.");
            }
            if (qty.compareTo(stillLeft) > 0) {
                throw new ReturnException("Only " + Quantity.format(stillLeft) + " " + line.unit() + " of "
                        + line.productName() + " is left to void.");
            }
            BigDecimal amount = qty.compareTo(stillLeft) == 0
                    // the last of the line: refund exactly what is left of what was paid for it
                    ? line.lineTotal().subtract(refundedSoFar.getOrDefault(line.lineNo(), Money.ZERO)).max(Money.ZERO)
                    : SaleLineRecord.totalOf(qty, line.unitPrice());
            lines.add(new ReturnLineRecord(lines.size() + 1, line.lineNo(), line.productId(), line.barcode(),
                    line.productName(), line.unit(), qty, line.unitPrice(), line.priceType(), line.vatable(), amount));
            if (line.vatable()) {
                vatableWithVat = vatableWithVat.add(amount);
            } else {
                exempt = exempt.add(amount);
            }
        }
        if (lines.isEmpty()) {
            throw new ReturnException("Everything on sale " + sale.invoiceNo() + " has already been voided.");
        }

        // VAT: worked out on this return's VATable items. When this void empties the sale, take what is
        // left of the sale's own VAT instead, so the sale and all its voids add up to exactly zero.
        BigDecimal vatable = Vat.netOf(vatableWithVat);
        boolean emptiesSale = true;
        Map<Integer, BigDecimal> after = new HashMap<>(left);
        for (ReturnLineRecord line : lines) {
            after.merge(line.saleLineNo(), line.quantity().negate(), BigDecimal::add);
        }
        for (BigDecimal q : after.values()) {
            emptiesSale &= q.signum() <= 0;
        }
        if (emptiesSale) {
            BigDecimal rest = sale.vatableSales();
            for (ReturnRecord r : earlier) {
                rest = rest.subtract(r.vatableSales());
            }
            if (!Money.isNegative(rest) && rest.compareTo(vatableWithVat) <= 0) {
                vatable = rest;
            }
        }
        BigDecimal vat = vatableWithVat.subtract(vatable);

        String returnNo = ReturnRecord.numberFor(sale.invoiceNo(), earlier.size() + 1);
        return new ReturnRecord(UUID.randomUUID().toString(), returnNo, sale.saleId(), sale.invoiceNo(),
                terminalId, userId, now, emptiesSale,   // full void = nothing is left on the sale after it
                refund == null ? PaymentMethod.CASH : refund.method(), refund == null ? null : refund.referenceNo(), lines,
                vatable, exempt, vat, vatableWithVat.add(exempt));
    }
}
