package reports.service;

import auth.permission.Permission;
import pos.contract.PriceType;
import pos.contract.SaleHistory;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import reports.model.ReportPeriod;
import reports.model.SalesBucket;
import reports.model.SalesSummary;
import shared.Money;
import shared.PhTime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Sales summary for any period (daily, monthly, yearly, custom) through ONE method,
 * {@link #salesFor}. Read-only. Needs VIEW_SALES_REPORTS.
 *
 * <p>Refunds are taken off ({@link pos.contract.NetSale}): every amount is what was sold minus what
 * was refunded of it, on the day of the SALE (a sale today refunded tomorrow lowers today's sales).
 */
public final class SalesReportService {

    /** A custom range longer than this many days is broken down by month instead of by day. */
    public static final int MAX_DAILY_ROWS = 62;

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US);

    private final SaleHistory history;
    private final pos.contract.ReturnHistory returns;   // null = refunds not taken off
    private final ReportsAccess access;

    public SalesReportService(SaleHistory history, ReportsAccess access) {
        this(history, null, access);
    }

    public SalesReportService(SaleHistory history, pos.contract.ReturnHistory returns, ReportsAccess access) {
        this.history = Objects.requireNonNull(history, "history");
        this.returns = returns;
        this.access = Objects.requireNonNull(access, "access");
    }

    /** Totals, VAT, retail / wholesale split and a per-day (or per-month) breakdown of {@code period}. */
    public SalesSummary salesFor(String sessionId, ReportPeriod period) throws ReportException {
        access.require(sessionId, Permission.VIEW_SALES_REPORTS);
        Objects.requireNonNull(period, "period");
        List<pos.contract.NetSale> sales = SalesSource.netIn(history, returns, period);

        boolean byMonth = period.kind() == ReportPeriod.Kind.YEAR
                || ChronoUnit.DAYS.between(period.from(), period.to()) + 1 > MAX_DAILY_ROWS;
        Map<LocalDate, Bucket> buckets = emptyBuckets(period, byMonth);

        BigDecimal total = Money.ZERO;
        BigDecimal vatable = Money.ZERO;
        BigDecimal exempt = Money.ZERO;
        BigDecimal vat = Money.ZERO;
        BigDecimal retail = Money.ZERO;
        BigDecimal wholesale = Money.ZERO;
        for (pos.contract.NetSale sale : sales) {   // refunds already taken off
            total = total.add(sale.total());
            vatable = vatable.add(sale.vatableSales());
            exempt = exempt.add(sale.vatExemptSales());
            vat = vat.add(sale.vatAmount());
            for (pos.contract.NetSale.Line line : sale.lines()) {
                if (line.sold().priceType() == PriceType.WHOLESALE) {
                    wholesale = wholesale.add(line.amount());
                } else {
                    retail = retail.add(line.amount());
                }
            }
            // The PH day of the SALE, not the UTC day (23:30 PH is still that day), nor the refund's day.
            LocalDate day = PhTime.of(sale.sale().completedAt()).toLocalDate();
            Bucket bucket = buckets.get(byMonth ? day.withDayOfMonth(1) : day);
            if (bucket != null) {   // always found for a sale inside the period
                bucket.transactions++;
                bucket.total = bucket.total.add(sale.total());
            }
        }

        List<SalesBucket> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Bucket> e : buckets.entrySet()) {
            rows.add(new SalesBucket(e.getKey(), e.getValue().label, e.getValue().transactions, e.getValue().total));
        }
        return new SalesSummary(period, sales.size(), total, vatable, exempt, vat, retail, wholesale, rows);
    }

    /** One empty row per day (or month) of the period, in date order, so days without sales show 0. */
    private static Map<LocalDate, Bucket> emptyBuckets(ReportPeriod period, boolean byMonth) {
        Map<LocalDate, Bucket> buckets = new LinkedHashMap<>();
        if (byMonth) {
            YearMonth last = YearMonth.from(period.to());
            for (YearMonth m = YearMonth.from(period.from()); !m.isAfter(last); m = m.plusMonths(1)) {
                buckets.put(m.atDay(1), new Bucket(MONTH_LABEL.format(m)));
            }
        } else {
            for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
                buckets.put(d, new Bucket(DAY_LABEL.format(d)));
            }
        }
        return buckets;
    }

    /** A breakdown row while it is being added up. */
    private static final class Bucket {
        final String label;
        int transactions;
        BigDecimal total = Money.ZERO;

        Bucket(String label) {
            this.label = label;
        }
    }
}
