package dashboard;

import inventory.contract.StockSnapshot;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import shared.Money;
import shared.PhTime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The numbers behind the dashboard, worked out from plain lists so they can be checked without a
 * database or JavaFX. No rules of other modules live here: sales come from pos.contract.SaleHistory
 * and stock from inventory.contract.StockOverview.
 */
public final class DashboardStats {

    private DashboardStats() { }

    /** One period's sales: how many sales, how much money, how many items. */
    public record Totals(int transactions, BigDecimal sales, BigDecimal items) {

        public static final Totals NONE = new Totals(0, Money.ZERO, BigDecimal.ZERO);

        /** The average sale, or zero when there were none. */
        public BigDecimal average() {
            return transactions == 0 ? Money.ZERO
                    : sales.divide(BigDecimal.valueOf(transactions), 2, RoundingMode.HALF_UP);
        }
    }

    /** Up, down or the same, for the small "vs yesterday" line under a number. */
    public enum Direction { UP, DOWN, SAME, NEW }

    /** A comparison: which way it went and the text to show, e.g. "12% more than ...". */
    public record Change(Direction direction, String text) { }

    /**
     * Totals of {@code sales}; {@code cashierUserId} null = every cashier, else only that cashier's sales.
     * Refunds are taken off (money and items), on the day of the sale; a sale still counts as a transaction.
     */
    public static Totals totals(List<pos.contract.NetSale> sales, String cashierUserId) {
        int count = 0;
        BigDecimal money = Money.ZERO;
        BigDecimal items = BigDecimal.ZERO;
        for (pos.contract.NetSale s : sales) {
            if (cashierUserId != null && !cashierUserId.equals(s.sale().cashierUserId())) {
                continue;
            }
            count++;
            money = money.add(s.total());
            items = items.add(s.items());
        }
        return new Totals(count, money, items);
    }

    /** Sales completed before {@code cutoff} (e.g. yesterday, up to this time of day). */
    public static List<SaleRecord> before(List<SaleRecord> sales, Instant cutoff) {
        List<SaleRecord> out = new ArrayList<>();
        for (SaleRecord s : sales) {
            if (s.completedAt().isBefore(cutoff)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * How {@code today} compares with {@code yesterday}, as a percentage.
     *
     * @param what what is being compared, for the text when yesterday had none ("sales")
     */
    public static Change change(BigDecimal today, BigDecimal yesterday, String what) {
        int cmp = today.compareTo(yesterday);
        if (yesterday.signum() == 0) {
            return today.signum() == 0 ? new Change(Direction.SAME, "No " + what + " yet, same as yesterday")
                    : new Change(Direction.NEW, "None by this time yesterday");
        }
        if (cmp == 0) {
            return new Change(Direction.SAME, "Same as this time yesterday");
        }
        BigDecimal pct = today.subtract(yesterday).abs().multiply(BigDecimal.valueOf(100))
                .divide(yesterday, 0, RoundingMode.HALF_UP);
        return cmp > 0 ? new Change(Direction.UP, pct + "% more than this time yesterday")
                : new Change(Direction.DOWN, pct + "% less than this time yesterday");
    }

    /** How many sales were completed in each hour of the day (PH time), index 0-23. */
    public static int[] perHour(List<SaleRecord> sales, String cashierUserId) {
        int[] hours = new int[24];
        for (SaleRecord s : sales) {
            if (cashierUserId == null || cashierUserId.equals(s.cashierUserId())) {
                hours[PhTime.of(s.completedAt()).getHour()]++;
            }
        }
        return hours;
    }

    /** Out-of-stock products first, then low-stock ones; A-Z inside each group. In-stock ones are left out. */
    public static List<StockSnapshot> alerts(List<StockSnapshot> stock) {
        List<StockSnapshot> out = new ArrayList<>();
        for (StockSnapshot s : stock) {
            if (s.level() != StockSnapshot.Level.IN_STOCK) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing((StockSnapshot s) -> s.level() == StockSnapshot.Level.OUT_OF_STOCK ? 0 : 1)
                .thenComparing(StockSnapshot::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public static long count(List<StockSnapshot> stock, StockSnapshot.Level level) {
        return stock.stream().filter(s -> s.level() == level).count();
    }

    /** The most recent {@code n} sales, newest first. */
    public static List<SaleRecord> latest(List<SaleRecord> sales, String cashierUserId, int n) {
        List<SaleRecord> mine = new ArrayList<>();
        for (SaleRecord s : sales) {
            if (cashierUserId == null || cashierUserId.equals(s.cashierUserId())) {
                mine.add(s);
            }
        }
        mine.sort(Comparator.comparing(SaleRecord::completedAt).reversed());
        return mine.subList(0, Math.min(n, mine.size()));
    }
}
