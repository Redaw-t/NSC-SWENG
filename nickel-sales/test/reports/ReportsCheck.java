package reports;

import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import auth.permission.AuthorizationService;
import auth.permission.Permission;
import inventory.contract.StockOverview;
import inventory.contract.StockSnapshot;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleHistory;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import reports.model.GroupTotal;
import reports.model.InventoryReport;
import reports.model.ProductRanking;
import reports.model.ProductSales;
import reports.model.ReportFilter;
import reports.model.ReportPeriod;
import reports.model.SalesSummary;
import reports.model.TransactionList;
import reports.service.CashierReportService;
import reports.service.InventoryReportService;
import reports.service.ProductPerformanceService;
import reports.service.ReportException;
import reports.service.ReportsAccess;
import reports.service.SalesReportService;
import reports.service.TransactionHistoryService;
import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SELF-TEST for Transaction &amp; Reports. Builds completed sales in memory (no database) and
 * checks the totals, the period boundaries in Philippine time, the top-N order and its
 * tie-breaks, grouping by cashier and terminal, the transaction filters, the inventory
 * report (with an in-memory StockOverview), and who may see what.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" reports.ReportsCheck
 * (macOS/Linux: ':' instead of ';'). Exit code 1 on failure.
 */
public class ReportsCheck {

    static int passed = 0;
    static int failed = 0;

    static final LocalDate NOV_4 = LocalDate.of(2026, 11, 4);

    // ---- the sales (PH time = UTC + 8) ----------------------------------------------------
    // S1 09:00 PH Nov 4, T01, cashier NSC-00002, cash.
    //    Bond Paper 2 x 100.00 retail VATable = 200.00; Rice 1 x 50.00 retail VAT-exempt = 50.00
    //    VAT 12% of 200.00 = 24.00; total 274.00
    static final SaleRecord S1 = sale("S1", "T01-2026-000001", "T01", "NSC-00002", "2026-11-04T01:00:00Z",
            PaymentMethod.CASH,
            line(1, "P-1", "Bond Paper", "2", "100.00", PriceType.RETAIL, true),
            line(2, "P-2", "Rice", "1", "50.00", PriceType.RETAIL, false));
    // S2 23:30 PH Nov 4 (15:30 UTC: still Nov 4 in PH), T02, cashier NSC-00006, GCash.
    //    Bond Paper (renamed) 12 x 90.00 wholesale = 1080.00; VAT 129.60; total 1209.60
    static final SaleRecord S2 = sale("S2", "T02-2026-000001", "T02", "NSC-00006", "2026-11-04T15:30:00Z",
            PaymentMethod.GCASH,
            line(1, "P-1", "Bond Paper (ream)", "12", "90.00", PriceType.WHOLESALE, true));
    // S3 00:00 PH Nov 5 (16:00 UTC Nov 4: already Nov 5 in PH), T01, NSC-00002, cash.
    //    Marker 5 x 20.00 = 100.00; VAT 12.00; total 112.00
    static final SaleRecord S3 = sale("S3", "T01-2026-000002", "T01", "NSC-00002", "2026-11-04T16:00:00Z",
            PaymentMethod.CASH,
            line(1, "P-3", "Marker", "5", "20.00", PriceType.RETAIL, true));
    // S4 23:59:59 PH Oct 31, T01, NSC-00002, cash. Rice 3 x 50.00 exempt = 150.00; total 150.00
    static final SaleRecord S4 = sale("S4", "T01-2026-000003", "T01", "NSC-00002", "2026-10-31T15:59:59Z",
            PaymentMethod.CASH,
            line(1, "P-2", "Rice", "3", "50.00", PriceType.RETAIL, false));

    static final List<SaleRecord> ALL = List.of(S1, S2, S3, S4);

    // ---- who is logged in ------------------------------------------------------------------
    static final AuthorizationService AUTHZ = new AuthorizationService();
    static final Map<String, User> SESSIONS = Map.of(
            "s-admin", user("NSC-00001", UserStatus.ACTIVE, UserRole.ADMIN),
            "s-inventory", user("NSC-00003", UserStatus.ACTIVE, UserRole.INVENTORY_STAFF),
            "s-cashier", user("NSC-00002", UserStatus.ACTIVE, UserRole.CASHIER),
            "s-inactive", user("NSC-00004", UserStatus.INACTIVE, UserRole.ADMIN));
    static final ReportsAccess ACCESS = ReportsAccess.of(SESSIONS::get, AUTHZ);
    static final String ADMIN = "s-admin";

    static final SaleHistory HISTORY = new MemorySaleHistory(ALL);
    static final TransactionHistoryService TRANSACTIONS = new TransactionHistoryService(HISTORY, ACCESS);
    static final SalesReportService SALES = new SalesReportService(HISTORY, ACCESS);
    static final ProductPerformanceService PRODUCTS = new ProductPerformanceService(HISTORY, ACCESS);
    static final CashierReportService CASHIERS = new CashierReportService(HISTORY, ACCESS);

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Transaction & Reports checks");
        System.out.println("=================================================================");

        // ---- periods -------------------------------------------------------------------
        check("Period: a day runs from PH midnight to the next PH midnight", () -> {
            ReportPeriod day = ReportPeriod.day(NOV_4);
            expectText(day.start().toString(), "2026-11-03T16:00:00Z");
            expectText(day.end().toString(), "2026-11-04T16:00:00Z");
        });

        check("Period: 23:30 PH belongs to that day; 00:00 PH belongs to the next", () -> {
            expectTrue(ReportPeriod.day(NOV_4).contains(S2.completedAt()), "23:30 PH Nov 4 is Nov 4");
            expectTrue(!ReportPeriod.day(NOV_4.plusDays(1)).contains(S2.completedAt()), "... not Nov 5");
            expectTrue(!ReportPeriod.day(NOV_4).contains(S3.completedAt()), "00:00 PH Nov 5 is not Nov 4");
            expectTrue(ReportPeriod.day(NOV_4.plusDays(1)).contains(S3.completedAt()), "... it is Nov 5");
        });

        check("Period: month, leap February, year and the screen's Monthly / Yearly choice", () -> {
            ReportPeriod feb = ReportPeriod.month(YearMonth.of(2028, 2));
            expectText(feb.from() + ".." + feb.to(), "2028-02-01..2028-02-29");
            ReportPeriod year = ReportPeriod.year(2026);
            expectText(year.from() + ".." + year.to(), "2026-01-01..2026-12-31");
            expectText(year.end().toString(), "2026-12-31T16:00:00Z");
            expectText(ReportPeriod.of(ReportPeriod.Kind.MONTH, NOV_4, null).label(), "November 2026");
            expectText(ReportPeriod.of(ReportPeriod.Kind.YEAR, NOV_4, null).label(), "2026");
            expectText(ReportPeriod.of(ReportPeriod.Kind.DAY, NOV_4, null).label(), "Nov 4, 2026");
            expectText(ReportPeriod.custom(NOV_4, NOV_4.plusDays(2)).label(), "Nov 4, 2026 - Nov 6, 2026");
        });

        check("Period: From after To, or a missing date, is refused", () -> {
            expectThrows(() -> ReportPeriod.custom(NOV_4, NOV_4.minusDays(1)), IllegalArgumentException.class);
            expectThrows(() -> ReportPeriod.of(ReportPeriod.Kind.CUSTOM, NOV_4, null), IllegalArgumentException.class);
            expectThrows(() -> ReportPeriod.of(ReportPeriod.Kind.DAY, null, null), IllegalArgumentException.class);
        });

        // ---- sales summary ---------------------------------------------------------------
        check("Summary (day): totals, VAT split and retail / wholesale split", () -> {
            SalesSummary s = SALES.salesFor(ADMIN, ReportPeriod.day(NOV_4));   // S1 + S2
            expectTrue(s.transactions() == 2, "2 sales, got " + s.transactions());
            expectMoney(s.totalIncome(), "1483.60");     // 274.00 + 1209.60
            expectMoney(s.vatableSales(), "1280.00");    // 200.00 + 1080.00
            expectMoney(s.vatExemptSales(), "50.00");
            expectMoney(s.vatAmount(), "153.60");        // 24.00 + 129.60
            expectMoney(s.retailAmount(), "250.00");     // 200.00 + 50.00 (lines, before VAT)
            expectMoney(s.wholesaleAmount(), "1080.00");
            expectMoney(s.vatableSales().add(s.vatExemptSales()).add(s.vatAmount()), "1483.60");
            expectTrue(s.breakdown().size() == 1, "one row for one day");
        });

        check("Summary (month): one row per day, days without sales show 0", () -> {
            SalesSummary s = SALES.salesFor(ADMIN, ReportPeriod.month(YearMonth.of(2026, 11)));   // S1, S2, S3
            expectTrue(s.transactions() == 3, "3 sales");
            expectMoney(s.totalIncome(), "1595.60");
            expectTrue(s.breakdown().size() == 30, "30 days in November");
            expectTrue(s.breakdown().get(0).transactions() == 0, "Nov 1: nothing");
            expectMoney(s.breakdown().get(0).total(), "0.00");
            expectTrue(s.breakdown().get(3).transactions() == 2, "Nov 4: S1 and S2 (23:30 PH)");
            expectMoney(s.breakdown().get(3).total(), "1483.60");
            expectTrue(s.breakdown().get(4).transactions() == 1, "Nov 5: S3 (00:00 PH)");
            expectText(s.breakdown().get(3).label(), "Nov 4, 2026");
        });

        check("Summary (year): one row per month; Oct 31 23:59:59 PH stays in October", () -> {
            SalesSummary s = SALES.salesFor(ADMIN, ReportPeriod.year(2026));
            expectTrue(s.transactions() == 4, "all 4 sales");
            expectMoney(s.totalIncome(), "1745.60");
            expectTrue(s.breakdown().size() == 12, "12 months");
            expectText(s.breakdown().get(9).label(), "Oct 2026");
            expectTrue(s.breakdown().get(9).transactions() == 1, "October: S4");
            expectMoney(s.breakdown().get(9).total(), "150.00");
            expectTrue(s.breakdown().get(10).transactions() == 3, "November: S1, S2, S3");
        });

        check("Summary (custom): Oct 31 - Nov 4 by day; a long custom range by month", () -> {
            SalesSummary s = SALES.salesFor(ADMIN, ReportPeriod.custom(LocalDate.of(2026, 10, 31), NOV_4));
            expectTrue(s.transactions() == 3, "S4, S1, S2");
            expectMoney(s.totalIncome(), "1633.60");
            expectTrue(s.breakdown().size() == 5, "5 days");
            SalesSummary longRange = SALES.salesFor(ADMIN,
                    ReportPeriod.custom(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 12, 31)));
            expectTrue(longRange.breakdown().size() == 12, "Jan to Dec by month, got " + longRange.breakdown().size());
        });

        check("Summary: an empty day is all zeros", () -> {
            SalesSummary s = SALES.salesFor(ADMIN, ReportPeriod.day(LocalDate.of(2026, 1, 1)));
            expectTrue(s.transactions() == 0, "no sales");
            expectMoney(s.totalIncome(), "0.00");
            expectMoney(s.wholesaleAmount(), "0.00");
        });

        // ---- top products ----------------------------------------------------------------
        check("Top products (November): order, quantity, amount, share and latest name", () -> {
            List<ProductSales> top = PRODUCTS.top(ADMIN, ReportPeriod.month(YearMonth.of(2026, 11)),
                    ProductRanking.BY_QUANTITY, 10);
            expectText(ids(top), "P-1,P-3,P-2");
            expectText(Quantity.format(top.get(0).quantity()), "14");   // 2 + 12
            expectMoney(top.get(0).amount(), "1280.00");
            expectText(top.get(0).name(), "Bond Paper (ream)");         // the name on its latest sale
            // Line amounts in November: 200 + 50 + 1080 + 100 = 1430
            expectMoney(top.get(0).sharePercent(), "89.51");            // 1280 / 1430
            expectMoney(top.get(1).sharePercent(), "6.99");             // 100 / 1430
            expectMoney(top.get(2).sharePercent(), "3.50");             // 50 / 1430
        });

        SalesReportCheckTies.run();

        check("Top products: n cuts the list; n out of range is refused", () -> {
            ReportPeriod nov = ReportPeriod.month(YearMonth.of(2026, 11));
            expectText(ids(PRODUCTS.top(ADMIN, nov, ProductRanking.BY_AMOUNT, 1)), "P-1");
            expectThrows(() -> PRODUCTS.top(ADMIN, nov, ProductRanking.BY_AMOUNT, 0), ReportException.class);
            expectThrows(() -> PRODUCTS.top(ADMIN, nov, ProductRanking.BY_AMOUNT,
                    ProductPerformanceService.MAX_N + 1), ReportException.class);
            expectTrue(PRODUCTS.top(ADMIN, ReportPeriod.day(LocalDate.of(2026, 1, 1)),
                    ProductRanking.BY_AMOUNT, 5).isEmpty(), "a day without sales has no products");
        });

        // ---- cashiers and terminals ------------------------------------------------------
        check("By cashier and by terminal (November): highest total first", () -> {
            ReportPeriod nov = ReportPeriod.month(YearMonth.of(2026, 11));
            List<GroupTotal> cashiers = CASHIERS.byCashier(ADMIN, nov);
            expectTrue(cashiers.size() == 2, "2 cashiers");
            expectText(cashiers.get(0).key(), "NSC-00006");
            expectTrue(cashiers.get(0).transactions() == 1, "NSC-00006: 1 sale");
            expectMoney(cashiers.get(0).total(), "1209.60");
            expectText(cashiers.get(1).key(), "NSC-00002");
            expectTrue(cashiers.get(1).transactions() == 2, "NSC-00002: S1 and S3");
            expectMoney(cashiers.get(1).total(), "386.00");   // 274.00 + 112.00

            List<GroupTotal> terminals = CASHIERS.byTerminal(ADMIN, nov);
            expectText(terminals.get(0).key() + "=" + terminals.get(0).total()
                    + "," + terminals.get(1).key() + "=" + terminals.get(1).total(), "T02=1209.60,T01=386.00");
        });

        // ---- transaction history ---------------------------------------------------------
        check("History: today's sales newest first; filter by cashier, terminal, payment", () -> {
            ReportPeriod day = ReportPeriod.day(NOV_4);
            TransactionList all = TRANSACTIONS.find(ADMIN, ReportFilter.of(day));
            expectText(invoices(all), "T02-2026-000001,T01-2026-000001");
            TransactionList byCashier = TRANSACTIONS.find(ADMIN, new ReportFilter(day, "NSC-00002", null, null, null));
            expectText(invoices(byCashier), "T01-2026-000001");
            expectText(String.join(",", byCashier.cashierIds()), "NSC-00002,NSC-00006");   // the list does not shrink
            expectText(String.join(",", byCashier.terminalIds()), "T01,T02");
            expectText(invoices(TRANSACTIONS.find(ADMIN, new ReportFilter(day, null, "t02", null, null))),
                    "T02-2026-000001");
            expectText(invoices(TRANSACTIONS.find(ADMIN, new ReportFilter(day, null, null, PaymentMethod.GCASH, null))),
                    "T02-2026-000001");
            expectText(invoices(TRANSACTIONS.find(ADMIN, new ReportFilter(day, null, null, PaymentMethod.MAYA, null))), "");
        });

        check("History: an exact invoice number is found on any date; part of one searches the period", () -> {
            ReportPeriod day = ReportPeriod.day(NOV_4);
            expectText(invoices(TRANSACTIONS.find(ADMIN, new ReportFilter(day, null, null, null, " t01-2026-000003 "))),
                    "T01-2026-000003");   // Oct 31, outside the period
            expectText(invoices(TRANSACTIONS.find(ADMIN, new ReportFilter(day, null, null, null, "-000001"))),
                    "T02-2026-000001,T01-2026-000001");
            expectText(invoices(TRANSACTIONS.find(ADMIN,
                    new ReportFilter(ReportPeriod.month(YearMonth.of(2026, 11)), null, null, null, "000002"))),
                    "T01-2026-000002");
            expectText(invoices(TRANSACTIONS.find(ADMIN, new ReportFilter(day, "NSC-00006", null, null, "T01-2026-000003"))),
                    "");   // the other filters still apply
        });

        check("History: open one sale with its lines and payments", () -> {
            SaleRecord s = TRANSACTIONS.open(ADMIN, "S1").orElseThrow();
            expectTrue(s.lines().size() == 2 && s.payments().size() == 1, "2 lines, 1 payment");
            expectTrue(TRANSACTIONS.open(ADMIN, "nope").isEmpty(), "unknown sale is empty");
            expectTrue(TRANSACTIONS.open(ADMIN, " ").isEmpty(), "blank sale ID is empty");
        });

        check("A database problem becomes a message, not a crash", () -> {
            SaleHistory broken = new MemorySaleHistory(List.of()) {
                @Override
                public List<SaleRecord> findCompleted(Instant from, Instant to) {
                    throw new IllegalStateException("connection refused");
                }
            };
            ReportException e = expectReportException(() ->
                    new SalesReportService(broken, ACCESS).salesFor(ADMIN, ReportPeriod.today()));
            expectTrue(e.getMessage().startsWith("The sales could not be loaded"), e.getMessage());
        });

        // ---- inventory report ------------------------------------------------------------
        StockOverview stock = () -> List.of(
                snapshot("P-1", "Bond Paper", "40", "10", StockSnapshot.Level.IN_STOCK),
                snapshot("P-2", "Rice", "5", "10", StockSnapshot.Level.LOW_STOCK),
                snapshot("P-3", "Marker", "0", "5", StockSnapshot.Level.OUT_OF_STOCK),
                snapshot("P-4", "Stapler", "10", "10", StockSnapshot.Level.LOW_STOCK));
        InventoryReportService inventory = new InventoryReportService(stock, ACCESS);

        check("Inventory report: all products, low stock and out of stock (no overlap)", () -> {
            InventoryReport r = inventory.current(ADMIN);
            expectTrue(r.all().size() == 4, "4 products");
            expectText(String.join(",", r.lowStock().stream().map(StockSnapshot::productId).toList()), "P-2,P-4");
            expectText(String.join(",", r.outOfStock().stream().map(StockSnapshot::productId).toList()), "P-3");
        });

        check("Inventory report: inventory staff and admin may see it; cashiers may not", () -> {
            inventory.current("s-inventory");
            inventory.current(ADMIN);
            expectDenied(() -> inventory.current("s-cashier"), "inventory reports");
            expectDenied(() -> inventory.current("s-inactive"), "inventory reports");
        });

        check("Inventory report: a database problem becomes a message", () -> {
            StockOverview broken = () -> {
                throw new IllegalStateException("connection refused");
            };
            ReportException e = expectReportException(() -> new InventoryReportService(broken, ACCESS).current(ADMIN));
            expectTrue(e.getMessage().startsWith("The stock could not be loaded"), e.getMessage());
        });

        // ---- permissions -----------------------------------------------------------------
        check("Permissions: admin sees every sales report", () -> {
            ReportPeriod day = ReportPeriod.day(NOV_4);
            SALES.salesFor(ADMIN, day);
            PRODUCTS.top(ADMIN, day, ProductRanking.BY_AMOUNT, 5);
            CASHIERS.byCashier(ADMIN, day);
            CASHIERS.byTerminal(ADMIN, day);
            TRANSACTIONS.find(ADMIN, ReportFilter.of(day));
            TRANSACTIONS.open(ADMIN, "S1");
        });

        check("Permissions: inventory staff and cashiers are refused every sales report (Story 5)", () -> {
            ReportPeriod day = ReportPeriod.day(NOV_4);
            for (String session : List.of("s-inventory", "s-cashier", "s-inactive")) {
                expectDenied(() -> SALES.salesFor(session, day), "sales reports");
                expectDenied(() -> PRODUCTS.top(session, day, ProductRanking.BY_AMOUNT, 5), "sales reports");
                expectDenied(() -> CASHIERS.byCashier(session, day), "sales reports");
                expectDenied(() -> CASHIERS.byTerminal(session, day), "sales reports");
                expectDenied(() -> TRANSACTIONS.find(session, ReportFilter.of(day)), "sales reports");
                expectDenied(() -> TRANSACTIONS.open(session, "S1"), "sales reports");
            }
        });

        check("Permissions: an expired session is told to log in again", () -> {
            ReportException e = expectReportException(() -> SALES.salesFor("s-gone", ReportPeriod.today()));
            expectTrue(e.getMessage().contains("log in again"), e.getMessage());
            expectReportException(() -> SALES.salesFor(null, ReportPeriod.today()));
        });

        check("Permissions per role: admin both, inventory staff inventory only, cashier none", () -> {
            expectTrue(AUTHZ.hasPermission(EnumSet.of(UserRole.ADMIN), Permission.VIEW_SALES_REPORTS)
                    && AUTHZ.hasPermission(EnumSet.of(UserRole.ADMIN), Permission.VIEW_INVENTORY_REPORTS), "admin");
            expectTrue(!AUTHZ.hasPermission(EnumSet.of(UserRole.INVENTORY_STAFF), Permission.VIEW_SALES_REPORTS)
                    && AUTHZ.hasPermission(EnumSet.of(UserRole.INVENTORY_STAFF), Permission.VIEW_INVENTORY_REPORTS),
                    "inventory staff");
            expectTrue(!AUTHZ.hasPermission(EnumSet.of(UserRole.CASHIER), Permission.VIEW_SALES_REPORTS)
                    && !AUTHZ.hasPermission(EnumSet.of(UserRole.CASHIER), Permission.VIEW_INVENTORY_REPORTS), "cashier");
        });

        // The Reports button checks only VIEW_INVENTORY_REPORTS (a module has one permission). That is
        // enough only while every role with sales reports also has inventory reports. If a new role
        // breaks this, ReportsModule needs an "either permission" check: this test says so.
        check("Guard: every role with VIEW_SALES_REPORTS also has VIEW_INVENTORY_REPORTS (Reports button)", () -> {
            for (UserRole role : UserRole.values()) {
                if (AUTHZ.hasPermission(EnumSet.of(role), Permission.VIEW_SALES_REPORTS)) {
                    expectTrue(AUTHZ.hasPermission(EnumSet.of(role), Permission.VIEW_INVENTORY_REPORTS),
                            role + " would see sales reports but no Reports button");
                }
            }
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** Top-N ties, on their own day so they don't disturb the sales above. */
    static final class SalesReportCheckTies {
        static void run() {
            // Dec 1: Zeta 2 x 10 = 20, alpha 2 x 10 = 20, Mid 2 x 15 = 30, Big 1 x 100 = 100
            SaleRecord ties = sale("S9", "T01-2026-000009", "T01", "NSC-00002", "2026-12-01T02:00:00Z",
                    PaymentMethod.CASH,
                    line(1, "P-Z", "Zeta", "2", "10.00", PriceType.RETAIL, true),
                    line(2, "P-A", "alpha", "2", "10.00", PriceType.RETAIL, true),
                    line(3, "P-M", "Mid", "2", "15.00", PriceType.RETAIL, true),
                    line(4, "P-B", "Big", "1", "100.00", PriceType.RETAIL, true));
            ProductPerformanceService products = new ProductPerformanceService(new MemorySaleHistory(List.of(ties)), ACCESS);
            ReportPeriod dec1 = ReportPeriod.day(LocalDate.of(2026, 12, 1));

            check("Top products ties: by quantity, equal quantities go by amount, then by name (any case)", () ->
                    expectText(ids(products.top(ADMIN, dec1, ProductRanking.BY_QUANTITY, 10)), "P-M,P-A,P-Z,P-B"));
            check("Top products ties: by amount, equal amounts go by quantity, then by name", () ->
                    expectText(ids(products.top(ADMIN, dec1, ProductRanking.BY_AMOUNT, 10)), "P-B,P-M,P-A,P-Z"));
            check("Top products ties: a product tied with the last one shown is cut, the same one every time", () -> {
                expectText(ids(products.top(ADMIN, dec1, ProductRanking.BY_QUANTITY, 2)), "P-M,P-A");
                expectText(ids(products.top(ADMIN, dec1, ProductRanking.BY_QUANTITY, 2)), "P-M,P-A");
            });
        }
    }

    // =========================== fixtures ===========================

    /** SaleHistory over a list, keeping the contract: from <= completedAt < to, oldest first. */
    static class MemorySaleHistory implements SaleHistory {
        private final List<SaleRecord> sales;

        MemorySaleHistory(List<SaleRecord> sales) {
            this.sales = sales;
        }

        @Override
        public Optional<SaleRecord> findById(String saleId) {
            return sales.stream().filter(s -> s.saleId().equals(saleId)).findFirst();
        }

        @Override
        public Optional<SaleRecord> findByInvoiceNo(String invoiceNo) {
            return sales.stream().filter(s -> s.invoiceNo().equalsIgnoreCase(invoiceNo)).findFirst();
        }

        @Override
        public List<SaleRecord> findCompleted(Instant from, Instant to) {
            return sales.stream()
                    .filter(s -> !s.completedAt().isBefore(from) && s.completedAt().isBefore(to))
                    .sorted(Comparator.comparing(SaleRecord::completedAt))
                    .toList();
        }
    }

    /** A sale line; {@code qty} x {@code price} is worked out here. */
    static Line line(int no, String productId, String name, String qty, String price, PriceType type, boolean vatable) {
        return new Line(no, productId, name, qty, price, type, vatable);
    }

    record Line(int no, String productId, String name, String qty, String price, PriceType type, boolean vatable) {
        SaleLineRecord record() {
            BigDecimal q = Quantity.of(qty);
            BigDecimal p = Money.of(price);
            return new SaleLineRecord(no, productId, null, name, "pc", q, p, type, vatable,
                    SaleLineRecord.totalOf(q, p));
        }
    }

    /** A completed sale with the totals worked out the POS way: 12% VAT on the VATable lines, rounded once. */
    static SaleRecord sale(String id, String invoiceNo, String terminal, String cashier, String completedAtUtc,
                           PaymentMethod method, Line... lines) {
        List<SaleLineRecord> records = new ArrayList<>();
        BigDecimal vatable = Money.ZERO;
        BigDecimal exempt = Money.ZERO;
        for (Line l : lines) {
            SaleLineRecord r = l.record();
            records.add(r);
            if (r.vatable()) {
                vatable = vatable.add(r.lineTotal());
            } else {
                exempt = exempt.add(r.lineTotal());
            }
        }
        BigDecimal vat = Money.round(vatable.multiply(new BigDecimal("0.12")));
        BigDecimal total = vatable.add(exempt).add(vat);
        PaymentRecord payment = new PaymentRecord(1, method, method.needsReference() ? "REF-" + id : null,
                total, total, Money.ZERO);
        return new SaleRecord(id, invoiceNo, terminal, cashier, Instant.parse(completedAtUtc), records,
                List.of(payment), vatable, exempt, vat, total);
    }

    static StockSnapshot snapshot(String id, String name, String onHand, String reorder, StockSnapshot.Level level) {
        return new StockSnapshot(id, name, null, "pc", Quantity.of(onHand), Quantity.of(reorder), level);
    }

    static User user(String id, UserStatus status, UserRole... roles) {
        EnumSet<UserRole> set = EnumSet.noneOf(UserRole.class);
        set.addAll(List.of(roles));
        return new User(id, id.toLowerCase(), "x", "Test", "User", set, status);
    }

    static String ids(List<ProductSales> rows) {
        return String.join(",", rows.stream().map(ProductSales::productId).toList());
    }

    static String invoices(TransactionList list) {
        return String.join(",", list.sales().stream().map(SaleRecord::invoiceNo).toList());
    }

    // =========================== check helpers (as in AppCheck) ===========================

    interface Body {
        void run() throws Exception;
    }

    static void check(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        }
    }

    static void expectText(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }

    static void expectTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    /** Same amount (1483.6 and 1483.60 are equal). */
    static void expectMoney(BigDecimal actual, String expected) {
        if (actual == null || actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    static void expectThrows(Body body, Class<? extends Throwable> type) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    static ReportException expectReportException(Body body) {
        try {
            body.run();
        } catch (ReportException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError("expected ReportException but got " + t);
        }
        throw new AssertionError("expected ReportException");
    }

    /** Refused, with the message naming what was refused. */
    static void expectDenied(Body body, String what) {
        ReportException e = expectReportException(body);
        if (!e.getMessage().contains("log in again") && !e.getMessage().contains(what)) {
            throw new AssertionError("wrong message: " + e.getMessage());
        }
    }
}
