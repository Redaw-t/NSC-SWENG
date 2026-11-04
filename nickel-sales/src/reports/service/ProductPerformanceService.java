package reports.service;

import auth.permission.Permission;
import pos.contract.SaleHistory;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import reports.model.ProductRanking;
import reports.model.ProductSales;
import reports.model.ReportPeriod;
import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Top-N products of a period, by quantity sold or by sales amount, with each product's share
 * of the period's sales. Read-only. Needs VIEW_SALES_REPORTS.
 *
 * <p>Ties are broken so the order never changes between two runs: by the other measure
 * (amount when ranking by quantity, quantity when ranking by amount), then by name, then by
 * product ID. Exactly {@code n} products are returned; one tied with the last shown is cut.
 */
public final class ProductPerformanceService {

    /** The most products one report may list. */
    public static final int MAX_N = 100;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SaleHistory history;
    private final ReportsAccess access;

    public ProductPerformanceService(SaleHistory history, ReportsAccess access) {
        this.history = Objects.requireNonNull(history, "history");
        this.access = Objects.requireNonNull(access, "access");
    }

    /** The {@code n} best-selling products of {@code period}, best first. */
    public List<ProductSales> top(String sessionId, ReportPeriod period, ProductRanking ranking, int n)
            throws ReportException {
        access.require(sessionId, Permission.VIEW_SALES_REPORTS);
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(ranking, "ranking");
        if (n < 1 || n > MAX_N) {
            throw new ReportException("Choose how many products to show (1 to " + MAX_N + ").");
        }

        // Add up every line per product. Sales come oldest first, so the name and unit kept
        // are the ones on the product's most recent sale.
        Map<String, Totals> byProduct = new LinkedHashMap<>();
        BigDecimal allSales = Money.ZERO;
        for (SaleRecord sale : SalesSource.completedIn(history, period)) {
            for (SaleLineRecord line : sale.lines()) {
                Totals t = byProduct.computeIfAbsent(line.productId(), id -> new Totals());
                t.name = line.productName();
                t.unit = line.unit();
                t.quantity = t.quantity.add(line.quantity());
                t.amount = t.amount.add(line.lineTotal());
                allSales = allSales.add(line.lineTotal());
            }
        }

        List<ProductSales> rows = new ArrayList<>();
        for (Map.Entry<String, Totals> e : byProduct.entrySet()) {
            Totals t = e.getValue();
            rows.add(new ProductSales(e.getKey(), t.name, t.unit, Quantity.normalize(t.quantity),
                    t.amount, share(t.amount, allSales)));
        }
        rows.sort(order(ranking));
        return List.copyOf(rows.subList(0, Math.min(n, rows.size())));
    }

    /** Best first, with the tie-breaks described on the class. */
    static Comparator<ProductSales> order(ProductRanking ranking) {
        Comparator<ProductSales> byQuantity = Comparator.comparing(ProductSales::quantity).reversed();
        Comparator<ProductSales> byAmount = Comparator.comparing(ProductSales::amount).reversed();
        Comparator<ProductSales> first = ranking == ProductRanking.BY_QUANTITY
                ? byQuantity.thenComparing(byAmount)
                : byAmount.thenComparing(byQuantity);
        return first.thenComparing(ProductSales::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ProductSales::productId);
    }

    /** {@code part} as a percentage of {@code whole}, 2 decimals; 0 when nothing was sold. */
    static BigDecimal share(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return Money.ZERO;
        }
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static final class Totals {
        String name;
        String unit;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal amount = Money.ZERO;
    }
}
