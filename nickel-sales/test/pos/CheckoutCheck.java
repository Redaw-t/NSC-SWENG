package pos;

import auth.contract.AuditLogger;
import inventory.contract.InsufficientStockException;
import inventory.contract.StockRecorder;
import pos.cart.CartException;
import pos.cart.PricingService;
import pos.cart.Sale;
import pos.cart.SaleStatus;
import pos.cart.SalesService;
import pos.checkout.CheckoutException;
import pos.checkout.CheckoutService;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.SaleRecord;
import pos.repository.InvoiceNumberGenerator;
import pos.repository.MySqlSaleRepository;
import shared.Database;
import shared.Money;
import shared.Quantity;
import stubs.StubInventory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * SELF-TEST for POS Phase 2: payments, invoice numbers and checkout.
 *
 * The payment checks run anywhere. The checkout checks need MySQL and are
 * skipped, not failed, when it is not reachable. They use a throw-away
 * terminal ID (e.g. "CK12345") and delete everything they wrote at the end.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" pos.CheckoutCheck
 * (macOS/Linux: ':' instead of ';'). Exit code 1 on failure.
 */
public class CheckoutCheck {

    static final String PAPER = "4800000000019";     // 250.00 / 235.00, stock 40
    static final String BALLPEN = "4800000000026";   //  12.00 /  10.00, stock 200
    static final String RICE = "4800000000040";      //  52.50 /  50.00, VAT-exempt, by kg
    static final String CASHIER = "NSC-00002";       // demo cashier from the SQL script

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    static final PaymentService payments = new PaymentService();
    static final List<String> auditLog = Collections.synchronizedList(new ArrayList<>());
    static final AuditLogger audit = (event, userId) -> auditLog.add(event + " by " + userId);

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - POS Phase 2 checks (checkout)");
        System.out.println("=================================================================");

        String terminal = "CK" + (10000 + (int) (Math.random() * 90000));
        StubInventory stock = new StubInventory();
        MySqlSaleRepository repo = new MySqlSaleRepository();
        CheckoutService checkout = new CheckoutService(repo, new InvoiceNumberGenerator(), stock, audit);

        check("Cash: must cover the total (VAT included); change is worked out", () -> {
            Sale sale = cart(new SalesService(new StubInventory(), terminal), PAPER, "1");   // 250.00 + 30.00 VAT
            PaymentRecord p = payments.recordPayment(sale, PaymentMethod.CASH, Money.of("500"), null);
            expect(p.amountTendered(), "500.00");
            expect(p.amountApplied(), "280.00");
            expect(p.change(), "220.00");
            expect(payments.recordPayment(sale, PaymentMethod.CASH, Money.of("280"), null).change(), "0.00");
            expectMessage(() -> payments.recordPayment(sale, PaymentMethod.CASH, Money.of("279.99"), null),
                    "Cash received (279.99) is less than the total (280.00).");
            expectMessage(() -> payments.recordPayment(sale, PaymentMethod.CASH, null, null),
                    "Enter the cash received.");
            expect(payments.computeChange(Money.of("638.75"), Money.of("1000")), "361.25");
        });

        check("GCash / Maya / QRPh: reference number required, exact amount, no change", () -> {
            Sale sale = cart(new SalesService(new StubInventory(), terminal), PAPER, "1");
            PaymentRecord g = payments.recordPayment(sale, PaymentMethod.GCASH, null, " 1009 876 543210 ");
            expectText(g.referenceNo(), "1009 876 543210");
            expect(g.amountTendered(), "280.00");
            expect(g.change(), "0.00");
            expectMessage(() -> payments.recordPayment(sale, PaymentMethod.MAYA, null, " "),
                    "Enter the Maya reference number.");
            expectMessage(() -> payments.recordPayment(sale, PaymentMethod.QRPH, null, "AB#12"),
                    "That QRPh reference number doesn't look right. Use only letters, numbers, spaces and "
                            + "dashes (4 to 50 characters).");
            expectTrue(!payments.validateReference(PaymentMethod.GCASH, "123"), "3 characters is too short");
            expectTrue(payments.validateReference(PaymentMethod.GCASH, "0001-ABCD"), "dashes are fine");
            // changing the method before finalizing is just asking again
            expectTrue(payments.recordPayment(sale, PaymentMethod.CASH, Money.of("300"), "ignored")
                    .referenceNo() == null, "cash keeps no reference");
        });

        check("Invoice number format: terminal, PH year, 6 digits", () -> {
            expectText(InvoiceNumberGenerator.format("T01", 2026, 1), "T01-2026-000001");
            expectText(InvoiceNumberGenerator.format("T02", 2027, 123456), "T02-2027-123456");
            expectThrows(() -> InvoiceNumberGenerator.checkTerminalId("TERMINAL-01"), IllegalArgumentException.class);
            expectThrows(() -> InvoiceNumberGenerator.checkTerminalId("T 1"), IllegalArgumentException.class);
        });

        check("Pay is refused before saving: empty sale, stock problem, changed total", () -> {
            StubInventory local = new StubInventory();
            SalesService pos = new SalesService(local, new PricingService(), terminal,
                    new CheckoutService(repo, new InvoiceNumberGenerator(), local, audit));
            Sale sale = pos.startSale(CASHIER);
            PaymentRecord any = new PaymentRecord(1, PaymentMethod.CASH, null, Money.ZERO, Money.ZERO, Money.ZERO);
            expectCart(() -> pos.finalizeSale(any), "Add an item before taking payment.");

            pos.addItem(BALLPEN, q("10"));
            PaymentRecord paid = payments.recordPayment(sale, PaymentMethod.CASH, Money.of("200"), null);
            local.add("P-0002", BALLPEN, "Ballpen Black", "pc", "12.00", "10.00", true, true, "3");   // sold elsewhere
            expectCart(() -> pos.finalizeSale(paid),
                    "Not enough stock for Ballpen Black: 3 left, 10 in the sale. Lower the quantity or remove it.");
            pos.updateQuantity("P-0002", q("3"));                       // total is now 36.00
            expectMessage(() -> pos.finalizeSale(paid),
                    "The total changed after the payment was entered. Enter the payment again.");
            expectTrue(pos.currentSale().isPresent() && sale.getStatus() == SaleStatus.OPEN, "sale still open");
            expectThrows(() -> new SalesService(local, terminal).finalizeSale(paid), IllegalStateException.class);
        });

        boolean db = databaseReachable();
        List<SaleRecord> saved = new ArrayList<>();
        int year = shared.PhTime.year(Instant.now());
        try {
            check("Cash sale is saved with invoice no. 000001, stock goes down, sale is logged", db, () -> {
                SalesService pos = new SalesService(stock, new PricingService(), terminal, checkout);
                Sale sale = cart(pos, PAPER, "2");
                pos.addItem(BALLPEN, q("6"));                             // wholesale
                PaymentRecord pay = payments.recordPayment(sale, PaymentMethod.CASH, Money.of("1000"), null);
                SaleRecord rec = pos.finalizeSale(pay);
                saved.add(rec);
                expectText(rec.invoiceNo(), terminal + "-" + year + "-000001");
                expectText(rec.saleId(), sale.getSaleId());
                expect(rec.vatableSales(), "560.00");
                expect(rec.vatAmount(), "67.20");
                expect(rec.total(), "627.20");
                expect(rec.payments().get(0).change(), "372.80");
                expectTrue(sale.getStatus() == SaleStatus.COMPLETED, "sale is COMPLETED");
                expectTrue(pos.currentSale().isEmpty(), "no open sale afterwards");
                expect(stock.stockOf("P-0001"), "38.000");
                expect(stock.stockOf("P-0002"), "194.000");
                expectTrue(auditLog.contains("SALE_COMPLETED " + rec.invoiceNo() + " by " + CASHIER), "logged");
                expectTrue(repo.findById(rec.saleId()).orElseThrow().equals(rec), "reads back exactly as saved");
            });

            check("Next sale gets 000002; find by invoice number", db, () -> {
                SalesService pos = new SalesService(stock, new PricingService(), terminal, checkout);
                Sale sale = cart(pos, BALLPEN, "1");
                SaleRecord rec = pos.finalizeSale(payments.recordPayment(sale, PaymentMethod.CASH, Money.of("20"), null));
                saved.add(rec);
                expectText(rec.invoiceNo(), terminal + "-" + year + "-000002");
                expectTrue(repo.findByInvoiceNo(" " + rec.invoiceNo() + " ").orElseThrow().equals(rec), "found");
                expectTrue(repo.findByInvoiceNo("NOPE-0000-000000").isEmpty(), "unknown invoice is empty");
            });

            check("Stock runs short while saving: no sale, no payment, no invoice number used", db, () -> {
                StockRecorder soldOut = (reference, lines, tx) -> {
                    throw new InsufficientStockException("P-0001", "Bond Paper A4 (ream)", Quantity.of("1"), Quantity.of("2"));
                };
                SalesService pos = new SalesService(stock, new PricingService(), terminal,
                        new CheckoutService(repo, new InvoiceNumberGenerator(), soldOut, audit));
                Sale sale = cart(pos, PAPER, "2");
                int nextBefore = nextNumber(terminal, year);
                int logBefore = auditLog.size();
                try {
                    pos.finalizeSale(payments.recordPayment(sale, PaymentMethod.CASH, Money.of("1000"), null));
                    throw new AssertionError("expected InsufficientStockException");
                } catch (InsufficientStockException e) {
                    expectText(e.getMessage(), "Not enough stock for Bond Paper A4 (ream): 1 left, 2 needed.");
                }
                expectTrue(count("SELECT COUNT(*) FROM Sale WHERE SaleID = ?", sale.getSaleId()) == 0, "no sale");
                expectTrue(count("SELECT COUNT(*) FROM SaleLine WHERE SaleID = ?", sale.getSaleId()) == 0, "no lines");
                expectTrue(count("SELECT COUNT(*) FROM Payment WHERE SaleID = ?", sale.getSaleId()) == 0, "no payment");
                expectTrue(nextNumber(terminal, year) == nextBefore, "invoice number not used up");
                expectTrue(auditLog.size() == logBefore, "nothing logged");
                expectTrue(sale.getStatus() == SaleStatus.OPEN && sale.getItems().size() == 1,
                        "sale is still open and unchanged, so the cashier can fix it");
            });

            check("A database error while saving also rolls everything back", db, () -> {
                StockRecorder broken = (reference, lines, tx) -> {
                    throw new SQLException("simulated failure");
                };
                SalesService pos = new SalesService(stock, new PricingService(), terminal,
                        new CheckoutService(repo, new InvoiceNumberGenerator(), broken, audit));
                Sale sale = cart(pos, PAPER, "1");
                int nextBefore = nextNumber(terminal, year);
                expectThrows(() -> pos.finalizeSale(payments.recordPayment(sale, PaymentMethod.CASH,
                        Money.of("300"), null)), RuntimeException.class);
                expectTrue(count("SELECT COUNT(*) FROM Sale WHERE SaleID = ?", sale.getSaleId()) == 0, "no sale");
                expectTrue(nextNumber(terminal, year) == nextBefore, "invoice number not used up");
            });

            check("No gap: the next good sale takes the number the failed ones did not use", db, () -> {
                SalesService pos = new SalesService(stock, new PricingService(), terminal, checkout);
                Sale sale = cart(pos, PAPER, "1");
                SaleRecord rec = pos.finalizeSale(payments.recordPayment(sale, PaymentMethod.CASH, Money.of("300"), null));
                saved.add(rec);
                expectText(rec.invoiceNo(), terminal + "-" + year + "-000003");
            });

            check("GCash sale with VAT-exempt kg items reads back exactly", db, () -> {
                SalesService pos = new SalesService(stock, new PricingService(), terminal, checkout);
                Sale sale = cart(pos, RICE, "1.5");                       // 78.75 VAT-exempt
                pos.addItem(BALLPEN, q("2"));                             // 24.00 VATable + 2.88 VAT
                SaleRecord rec = pos.finalizeSale(payments.recordPayment(sale, PaymentMethod.GCASH, null, "1009876543210"));
                saved.add(rec);
                SaleRecord back = repo.findById(rec.saleId()).orElseThrow();
                expectTrue(back.equals(rec), "reads back exactly as saved");
                expect(back.vatExemptSales(), "78.75");
                expect(back.vatableSales(), "24.00");
                expect(back.vatAmount(), "2.88");
                expect(back.total(), "105.63");
                expect(back.lines().get(0).quantity(), "1.500");
                expectText(back.payments().get(0).method() + " " + back.payments().get(0).referenceNo(),
                        "GCASH 1009876543210");
            });

            check("Sync (SaleExport): unsynced oldest first; markSynced removes them; safe to repeat", db, () -> {
                List<String> mine = saved.stream().map(SaleRecord::saleId).toList();
                List<SaleRecord> all = repo.findUnsynced(100000);
                for (int i = 1; i < all.size(); i++) {
                    expectTrue(!all.get(i).completedAt().isBefore(all.get(i - 1).completedAt()), "oldest first");
                }
                List<String> unsynced = all.stream().map(SaleRecord::saleId).filter(mine::contains).toList();
                expectTrue(unsynced.size() == mine.size() && unsynced.containsAll(mine), "all four are listed");
                Instant now = Instant.now();
                repo.markSynced(mine.get(0), now);
                repo.markSynced(mine.get(0), now.plusSeconds(60));       // again: no error, first time kept
                expectTrue(repo.findUnsynced(100000).stream().noneMatch(s -> s.saleId().equals(mine.get(0))),
                        "synced sale is no longer listed");
                expectTrue(repo.findById(mine.get(0)).isPresent(), "but it is still there");
                expectTrue(repo.findUnsynced(0).isEmpty(), "limit 0 gives nothing");
            });

            check("Reports (SaleHistory): sales in a date range, 'to' not included", db, () -> {
                Instant from = saved.get(0).completedAt();
                Instant last = saved.get(saved.size() - 1).completedAt();
                List<String> found = repo.findCompleted(from, last.plusMillis(1)).stream()
                        .map(SaleRecord::saleId).filter(id -> saved.stream().anyMatch(s -> s.saleId().equals(id))).toList();
                expectTrue(found.size() == saved.size(), "all of them in range");
                expectTrue(repo.findCompleted(from, from).isEmpty(), "empty range");
            });

            check("Invoice numbers restart each year and are separate per terminal", db, () -> {
                InvoiceNumberGenerator gen = new InvoiceNumberGenerator();
                Instant in2030 = Instant.parse("2030-06-01T00:00:00Z");
                Instant newYearPh = Instant.parse("2030-12-31T16:30:00Z");   // already 2031 in PH
                expectText(Database.inTransaction(tx -> gen.nextInvoiceNo(tx, terminal, in2030)), terminal + "-2030-000001");
                expectText(Database.inTransaction(tx -> gen.nextInvoiceNo(tx, terminal, in2030)), terminal + "-2030-000002");
                expectText(Database.inTransaction(tx -> gen.nextInvoiceNo(tx, terminal, newYearPh)), terminal + "-2031-000001");
                String other = terminal + "B";
                expectText(Database.inTransaction(tx -> gen.nextInvoiceNo(tx, other, in2030)), other + "-2030-000001");
            });

            check("Two sales at the same moment never get the same number", db, () -> {
                InvoiceNumberGenerator gen = new InvoiceNumberGenerator();
                Instant in2032 = Instant.parse("2032-03-01T00:00:00Z");
                ExecutorService pool = Executors.newFixedThreadPool(4);
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    results.add(pool.submit(() -> Database.inTransaction(tx -> gen.nextInvoiceNo(tx, terminal, in2032))));
                }
                Set<String> numbers = new TreeSet<>();
                for (Future<String> f : results) {
                    numbers.add(f.get());
                }
                pool.shutdown();
                expectTrue(numbers.size() == 20, "20 different numbers");
                expectTrue(numbers.contains(terminal + "-2032-000001") && numbers.contains(terminal + "-2032-000020"),
                        "000001 to 000020 with no gaps");
            });
        } finally {
            if (db) {
                cleanUp(terminal);
            }
        }

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed"
                + (skipped > 0 ? ", " + skipped + " skipped (MySQL not reachable)" : ""));
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------- helpers ------------------------------- */

    /** Starts a sale for the demo cashier and adds {@code qty} of one product. */
    static Sale cart(SalesService pos, String barcode, String qty) throws Exception {
        Sale sale = pos.startSale(CASHIER);
        pos.addItem(barcode, q(qty));
        return sale;
    }

    static BigDecimal q(String quantity) {
        return Quantity.of(quantity);
    }

    static boolean databaseReachable() {
        try (Connection c = Database.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    static int nextNumber(String terminal, int year) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT NextNumber FROM InvoiceSequence WHERE TerminalID = ? AND InvoiceYear = ?")) {
            ps.setString(1, terminal);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
    }

    static int count(String sql, String param) throws SQLException {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Deletes everything this run wrote (its terminal IDs start with {@code terminal}). */
    static void cleanUp(String terminal) {
        String like = terminal + "%";
        String sub = "SELECT SaleID FROM Sale WHERE TerminalID LIKE ?";
        try (Connection c = Database.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM Payment WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM SaleLine WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM Sale WHERE TerminalID LIKE ?",
                    "DELETE FROM InvoiceSequence WHERE TerminalID LIKE ?")) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, like);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.out.println(" [WARN] could not clean up test data for " + terminal + ": " + e);
        }
    }

    interface Body {
        void run() throws Exception;
    }

    static void check(String name, Body body) {
        check(name, true, body);
    }

    static void check(String name, boolean canRun, Body body) {
        if (!canRun) {
            skipped++;
            System.out.println(" [SKIP] " + name);
            return;
        }
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        }
    }

    static void expect(BigDecimal actual, String expected) {
        if (actual.compareTo(new BigDecimal(expected)) != 0 || !actual.toPlainString().equals(expected)) {
            throw new AssertionError("expected " + expected + " but was " + actual.toPlainString());
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

    /** Expects a CheckoutException with exactly this message. */
    static void expectMessage(Body body, String message) {
        expectException(body, CheckoutException.class, message);
    }

    /** Expects a CartException with exactly this message. */
    static void expectCart(Body body, String message) {
        expectException(body, CartException.class, message);
    }

    static void expectException(Body body, Class<? extends Exception> type, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                expectText(t.getMessage(), message);
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " \"" + message + "\" but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " \"" + message + "\"");
    }
}
