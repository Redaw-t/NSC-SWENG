package pos.contract;

import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CONTRACT — a completed sale with its refunds (POS voids) taken off: what the sale is really worth.
 * Every sales total (Reports, Dashboard) is worked out from these, so a refund always lowers the sales
 * of the DAY OF THE SALE, whenever it was made (a sale today refunded tomorrow lowers today's sales).
 * The sale itself never changes; this only reads it with its returns.
 *
 * @param sale    the completed sale
 * @param returns its returns (any date), oldest first; empty when nothing was refunded
 */
public record NetSale(SaleRecord sale, List<ReturnRecord> returns) {

    public NetSale {
        Objects.requireNonNull(sale, "sale");
        returns = List.copyOf(returns == null ? List.of() : returns);
        for (ReturnRecord r : returns) {
            if (!r.saleId().equals(sale.saleId())) {
                throw new IllegalArgumentException("Return " + r.returnNo() + " is not of sale " + sale.invoiceNo());
            }
        }
    }

    /**
     * Each sale with its own returns. {@code returns} may hold returns of other sales too (they are
     * ignored). Same order as {@code sales}.
     */
    public static List<NetSale> of(List<SaleRecord> sales, List<ReturnRecord> returns) {
        Map<String, List<ReturnRecord>> bySale = new HashMap<>();
        for (ReturnRecord r : returns == null ? List.<ReturnRecord>of() : returns) {
            bySale.computeIfAbsent(r.saleId(), id -> new ArrayList<>()).add(r);
        }
        List<NetSale> out = new ArrayList<>();
        for (SaleRecord s : sales) {
            out.add(new NetSale(s, bySale.getOrDefault(s.saleId(), List.of())));
        }
        return out;
    }

    /** Amount refunded on this sale so far (positive). */
    public BigDecimal refunded() {
        BigDecimal sum = Money.ZERO;
        for (ReturnRecord r : returns) {
            sum = sum.add(r.total());
        }
        return sum;
    }

    /** What the customer paid minus what was refunded, VAT included. */
    public BigDecimal total() {
        return sale.total().subtract(refunded());
    }

    public BigDecimal vatableSales() {
        BigDecimal v = sale.vatableSales();
        for (ReturnRecord r : returns) {
            v = v.subtract(r.vatableSales());
        }
        return v;
    }

    public BigDecimal vatExemptSales() {
        BigDecimal v = sale.vatExemptSales();
        for (ReturnRecord r : returns) {
            v = v.subtract(r.vatExemptSales());
        }
        return v;
    }

    public BigDecimal vatAmount() {
        BigDecimal v = sale.vatAmount();
        for (ReturnRecord r : returns) {
            v = v.subtract(r.vatAmount());
        }
        return v;
    }

    /** True when everything on the sale was refunded (nothing is left). */
    public boolean fullyRefunded() {
        for (Line line : lines()) {
            if (line.quantity().signum() > 0) {
                return false;
            }
        }
        return true;
    }

    /** Items still sold (quantities added up, refunds taken off). */
    public BigDecimal items() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line line : lines()) {
            sum = sum.add(line.quantity());
        }
        return Quantity.normalize(sum);
    }

    /** One line of the sale with what was refunded of it taken off. */
    public record Line(SaleLineRecord sold, BigDecimal quantity, BigDecimal amount) { }

    /** The sale's lines, in order, each with its refunded quantity and amount taken off (0 when fully refunded). */
    public List<Line> lines() {
        Map<Integer, BigDecimal[]> back = new HashMap<>();   // sale line no. -> {quantity, amount} refunded
        for (ReturnRecord r : returns) {
            for (ReturnLineRecord rl : r.lines()) {
                BigDecimal[] t = back.computeIfAbsent(rl.saleLineNo(), k -> new BigDecimal[] {BigDecimal.ZERO, Money.ZERO});
                t[0] = t[0].add(rl.quantity());
                t[1] = t[1].add(rl.lineTotal());
            }
        }
        List<Line> out = new ArrayList<>();
        for (SaleLineRecord l : sale.lines()) {
            BigDecimal[] t = back.getOrDefault(l.lineNo(), new BigDecimal[] {BigDecimal.ZERO, Money.ZERO});
            out.add(new Line(l, Quantity.normalize(l.quantity().subtract(t[0])), l.lineTotal().subtract(t[1])));
        }
        return out;
    }

    /** Sale ID -> its NetSale, keeping {@code sales}' order. */
    public static Map<String, NetSale> byId(List<NetSale> sales) {
        Map<String, NetSale> out = new LinkedHashMap<>();
        for (NetSale n : sales) {
            out.put(n.sale().saleId(), n);
        }
        return out;
    }
}
