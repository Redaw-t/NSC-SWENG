package pos;

import shared.Money;
import shared.PhTime;
import shared.Quantity;
import shared.Database;
import pos.contract.InsufficientStockException;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.ProductInfo;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.stub.StubInventory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SELF-TEST for POS Phase 0: shared money/quantity/time rules, the POS
 * contracts (and the rules their records enforce), the test stand-ins, the
 * POS tables, and Database.inTransaction.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" pos.PosContractCheck
 * (macOS/Linux: ':' instead of ';'). The database checks need MySQL and
 * are skipped, not failed, when it is not reachable. Exit code 1 on failure.
 */
public class PosContractCheck {

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - POS Phase 0 checks (contracts)");
        System.out.println("=================================================================");

        check("Money rounds half-up to 2 decimals", () -> {
            expect(Money.of("1.005"), "1.01");
            expect(Money.of("1.004"), "1.00");
            expect(Money.round(new BigDecimal("99.995")), "100.00");
            expectText(Money.format(Money.of("1234.5")), "1,234.50");
        });

        check("Quantity keeps 3 decimals and displays without trailing zeros", () -> {
            expect(Quantity.of("2"), "2.000");
            expectText(Quantity.format(Quantity.of("3")), "3");
            expectText(Quantity.format(Quantity.of("1.50")), "1.5");
            expectText(Quantity.format(Quantity.of("0.125")), "0.125");
        });

        check("ProductInfo rounds prices and refuses negative ones", () -> {
            ProductInfo p = new ProductInfo("X", null, "Thing", "pc", new BigDecimal("9.999"),
                    null, true, true, new BigDecimal("1"));
            expect(p.retailPrice(), "10.00");
            expectTrue(!p.hasWholesalePrice(), "no wholesale price");
            expectThrows(() -> new ProductInfo("X", null, "Thing", "pc", new BigDecimal("-1"),
                    null, true, true, BigDecimal.ONE), IllegalArgumentException.class);
        });

        check("SaleRecord needs at least one line and cannot be changed afterwards", () -> {
            expectThrows(() -> sale(List.of()), IllegalArgumentException.class);
            List<SaleLineRecord> lines = new ArrayList<>(List.of(line(1, "P-0002", "2")));
            SaleRecord s = sale(lines);
            lines.add(line(2, "P-0002", "1"));                    // changing the original list...
            expectTrue(s.lines().size() == 1, "sale copied its lines");   // ...does not change the sale
            expectThrows(() -> s.lines().add(line(3, "P-0002", "1")), UnsupportedOperationException.class);
        });

        check("PhTime: stored in UTC, dates and years in Philippine time", () -> {
            Instant newYearsEvePh = Instant.parse("2026-12-31T16:30:00Z");   // Jan 1 2027, 12:30 AM in PH
            expectTrue(PhTime.year(newYearsEvePh) == 2027, "PH year is 2027");
            expectText(PhTime.startOf(LocalDate.of(2026, 10, 24)).toString(), "2026-10-23T16:00:00Z");
            expectText(PhTime.format(Instant.parse("2026-10-24T07:23:00Z")), "Oct 24, 2026 3:23 PM");
        });

        check("PaymentRecord: cash gives change, e-wallets need a reference number", () -> {
            PaymentRecord cash = new PaymentRecord(1, PaymentMethod.CASH, null,
                    Money.of("500"), Money.of("349.75"), Money.of("150.25"));
            expect(cash.change(), "150.25");
            PaymentRecord gcash = new PaymentRecord(1, PaymentMethod.GCASH, "  1234 5678 9012 ",
                    Money.of("349.75"), Money.of("349.75"), Money.ZERO);
            expectText(gcash.referenceNo(), "1234 5678 9012");
            expectTrue(new PaymentRecord(1, PaymentMethod.CASH, "  ", Money.ZERO, Money.ZERO, Money.ZERO)
                    .referenceNo() == null, "blank reference becomes null");
            for (PaymentMethod m : List.of(PaymentMethod.GCASH, PaymentMethod.MAYA, PaymentMethod.QRPH)) {
                expectThrows(() -> new PaymentRecord(1, m, null, Money.of("10"), Money.of("10"), Money.ZERO),
                        IllegalArgumentException.class);
            }
            expectThrows(() -> new PaymentRecord(1, PaymentMethod.CASH, "REF1",
                    Money.of("10"), Money.of("10"), Money.ZERO), IllegalArgumentException.class);
            expectThrows(() -> new PaymentRecord(1, PaymentMethod.MAYA, "REF1",
                    Money.of("20"), Money.of("10"), Money.of("10")), IllegalArgumentException.class);
            expectThrows(() -> new PaymentRecord(1, PaymentMethod.CASH, null,
                    Money.of("20"), Money.of("10"), Money.of("9")), IllegalArgumentException.class);
            expectThrows(() -> new PaymentRecord(1, PaymentMethod.QRPH, "X".repeat(51),
                    Money.of("10"), Money.of("10"), Money.ZERO), IllegalArgumentException.class);
        });

        check("SaleLineRecord: quantity above 0 and lineTotal = quantity x unitPrice", () -> {
            SaleLineRecord rice = new SaleLineRecord(1, "P-0004", "4800000000040", "Rice (per kg)", "kg",
                    Quantity.of("1.5"), Money.of("52.50"), PriceType.RETAIL, false, Money.of("78.75"));
            expect(rice.lineTotal(), "78.75");
            expect(SaleLineRecord.totalOf(Quantity.of("0.333"), Money.of("10.00")), "3.33");
            expectThrows(() -> new SaleLineRecord(1, "P", null, "P", "pc", Quantity.of("2"), Money.of("10"),
                    PriceType.RETAIL, true, Money.of("21")), IllegalArgumentException.class);
            expectThrows(() -> new SaleLineRecord(1, "P", null, "P", "pc", Quantity.of("0"), Money.of("10"),
                    PriceType.RETAIL, true, Money.ZERO), IllegalArgumentException.class);
        });

        check("SaleRecord: VAT totals add up to the total and payments cover it", () -> {
            // 6 ballpens at wholesale 10.00 (VATable) + 1.5 kg rice at 52.50 (VAT-exempt)
            List<SaleLineRecord> lines = List.of(
                    new SaleLineRecord(1, "P-0002", "4800000000026", "Ballpen Black", "pc", Quantity.of("6"),
                            Money.of("10.00"), PriceType.WHOLESALE, true, Money.of("60.00")),
                    new SaleLineRecord(2, "P-0004", "4800000000040", "Rice (per kg)", "kg", Quantity.of("1.5"),
                            Money.of("52.50"), PriceType.RETAIL, false, Money.of("78.75")));
            SaleRecord s = sale(lines);                // prices are before VAT; VAT is added
            expect(s.vatableSales(), "60.00");
            expect(s.vatExemptSales(), "78.75");
            expect(s.subtotal(), "138.75");
            expect(s.vatAmount(), "7.20");             // 12% of 60.00
            expect(s.total(), "145.95");               // 138.75 + 7.20
            expectThrows(() -> new SaleRecord(s.saleId(), s.invoiceNo(), s.terminalId(), s.cashierUserId(),
                    s.completedAt(), s.lines(), s.payments(), s.vatableSales(), s.vatExemptSales(),
                    s.vatAmount(), Money.of("140.00")), IllegalArgumentException.class);           // wrong total
            expectThrows(() -> new SaleRecord(s.saleId(), s.invoiceNo(), s.terminalId(), s.cashierUserId(),
                    s.completedAt(), s.lines(), List.of(new PaymentRecord(1, PaymentMethod.CASH, null,
                            Money.of("138.75"), Money.of("138.75"), Money.ZERO)),
                    s.vatableSales(), s.vatExemptSales(), s.vatAmount(), Money.of("138.75")),
                    IllegalArgumentException.class);                                               // VAT left out
            expectThrows(() -> new SaleRecord(s.saleId(), s.invoiceNo(), s.terminalId(), s.cashierUserId(),
                    s.completedAt(), s.lines(), s.payments(), Money.of("61.00"), s.vatExemptSales(),
                    s.vatAmount(), s.total()), IllegalArgumentException.class);                    // VAT doesn't add up
            expectThrows(() -> new SaleRecord(s.saleId(), s.invoiceNo(), s.terminalId(), s.cashierUserId(),
                    s.completedAt(), s.lines(),
                    List.of(new PaymentRecord(1, PaymentMethod.CASH, null, Money.of("100"), Money.of("100"),
                            Money.ZERO)),
                    s.vatableSales(), s.vatExemptSales(), s.vatAmount(), s.total()),
                    IllegalArgumentException.class);                                               // underpaid
            expectThrows(() -> sale(List.of(line(2, "P-0002", "1"))), IllegalArgumentException.class);  // numbering
            expectThrows(() -> new SaleRecord(s.saleId(), s.invoiceNo(), s.terminalId(), s.cashierUserId(),
                    s.completedAt(), s.lines(), List.of(), s.vatableSales(), s.vatExemptSales(),
                    s.vatAmount(), s.total()), IllegalArgumentException.class);                    // no payment
        });

        StubInventory stub = new StubInventory();

        check("SaleRecord: sales saved under the old rule (VAT inside prices) still load", () -> {
            List<SaleLineRecord> lines = List.of(
                    new SaleLineRecord(1, "P-0002", null, "Ballpen Black", "pc", Quantity.of("6"),
                            Money.of("10.00"), PriceType.WHOLESALE, true, Money.of("60.00")),
                    new SaleLineRecord(2, "P-0004", null, "Rice (per kg)", "kg", Quantity.of("1.5"),
                            Money.of("52.50"), PriceType.RETAIL, false, Money.of("78.75")));
            PaymentRecord paid = new PaymentRecord(1, PaymentMethod.CASH, null, Money.of("138.75"),
                    Money.of("138.75"), Money.ZERO);
            SaleRecord old = new SaleRecord(UUID.randomUUID().toString(), "T99-2026-000002", "T99", "NSC-00002",
                    Instant.now(), lines, List.of(paid),
                    Money.of("53.57"), Money.of("78.75"), Money.of("6.43"), Money.of("138.75"));   // 60.00 / 1.12
            expect(old.total(), "138.75");
        });

        check("Stand-in: find by barcode, by ID, and search", () -> {
            expectText(stub.findByBarcode("4800000000019").orElseThrow().name(), "Bond Paper A4 (ream)");
            expectText(stub.findByBarcode(" 4800000000026 ").orElseThrow().productId(), "P-0002");
            expectTrue(stub.findByBarcode("0000000000000").isEmpty(), "unknown barcode is empty");
            expectTrue(stub.findById("P-0007").orElseThrow().barcode() == null, "glue stick has no barcode");
            expectTrue(stub.search("glue", 10).size() == 1, "search by name");
            expectTrue(stub.search("calculator", 10).isEmpty(), "inactive products are not searchable");
            expectTrue(!stub.findByBarcode("4800000000064").orElseThrow().active(),
                    "inactive products are still found by barcode");
        });

        check("Stand-in: stock-out deducts stock", () -> {
            stub.recordSale(sale(List.of(line(1, "P-0002", "5"), line(2, "P-0004", "1.5"))), null);
            expect(stub.stockOf("P-0002"), "195.000");
            expect(stub.stockOf("P-0004"), "99.000");
        });

        check("Stand-in: stock-out refuses the whole sale if any line is short", () -> {
            BigDecimal paperBefore = stub.stockOf("P-0001");
            try {
                stub.recordSale(sale(List.of(line(1, "P-0001", "2"), line(2, "P-0005", "1"))), null);
                throw new AssertionError("expected InsufficientStockException");
            } catch (InsufficientStockException e) {
                expectText(e.getProductId(), "P-0005");
                expectText(e.getMessage(), "Not enough stock for Masking Tape 1 inch: 0 left, 1 needed.");
            }
            expect(stub.stockOf("P-0001"), paperBefore.toPlainString());   // nothing deducted
        });

        check("Stand-in: two lines of the same product are added up", () -> {
            stub.add("P-0099", "123", "Test Item", "pc", "1.00", null, true, true, "3");
            expectThrows(() -> stub.recordSale(sale(List.of(line(1, "P-0099", "2"), line(2, "P-0099", "2"))), null),
                    InsufficientStockException.class);
        });

        boolean db = databaseReachable();
        check("POS tables are up to date (run setup if this fails)", db, () -> {
            for (String[] column : new String[][] {
                    {"Sale", "SaleID"}, {"Sale", "SyncedAt"}, {"SaleLine", "PriceType"}, {"SaleLine", "Vatable"},
                    {"Payment", "Method"}, {"Payment", "ReferenceNo"}, {"InvoiceSequence", "NextNumber"}}) {
                expectTrue(columnExists(column[0], column[1]),
                        column[0] + "." + column[1] + " is missing - run setup.bat to update the database");
            }
        });
        check("Database.inTransaction commits when the work succeeds", db, () -> {
            String terminal = "__T" + (System.nanoTime() % 100000);
            try {
                Database.inTransaction(tx -> insertSequence(tx, terminal));
                expectTrue(sequenceExists(terminal), "row was committed");
            } finally {
                deleteSequence(terminal);
            }
        });
        check("Database.inTransaction rolls back everything when the work fails", db, () -> {
            String terminal = "__T" + (System.nanoTime() % 100000);
            try {
                Database.inTransaction(tx -> {
                    insertSequence(tx, terminal);
                    throw new InsufficientStockException("P", "Test", BigDecimal.ZERO, BigDecimal.ONE);
                });
                throw new AssertionError("expected the exception to come back out");
            } catch (InsufficientStockException expected) {
                expectTrue(!sequenceExists(terminal), "row was rolled back");
            } finally {
                deleteSequence(terminal);
            }
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed"
                + (skipped > 0 ? ", " + skipped + " skipped (MySQL not reachable)" : ""));
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------- helpers ------------------------------- */

    static SaleLineRecord line(int no, String productId, String qty) {
        BigDecimal q = Quantity.of(qty);
        return new SaleLineRecord(no, productId, null, productId, "pc", q, Money.of("1.00"),
                PriceType.RETAIL, true, Money.round(q));
    }

    /** A cash sale paid exactly, with the VAT totals worked out as SaleRecord describes. */
    static SaleRecord sale(List<SaleLineRecord> lines) {
        BigDecimal exempt = lines.stream().filter(l -> !l.vatable())
                .map(SaleLineRecord::lineTotal).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal vatable = lines.stream().filter(SaleLineRecord::vatable)
                .map(SaleLineRecord::lineTotal).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal vat = vatable.multiply(new BigDecimal("0.12")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = vatable.add(exempt).add(vat);
        return new SaleRecord(UUID.randomUUID().toString(), "T99-2026-000001", "T99", "NSC-00002",
                Instant.now(), lines,
                List.of(new PaymentRecord(1, PaymentMethod.CASH, null, total, total, Money.ZERO)),
                vatable, exempt, vat, total);
    }

    static boolean databaseReachable() {
        try (Connection c = Database.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    static boolean columnExists(String table, String column) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static Void insertSequence(Connection tx, String terminal) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO InvoiceSequence (TerminalID, InvoiceYear, NextNumber) VALUES (?, 2000, 1)")) {
            ps.setString(1, terminal);
            ps.executeUpdate();
        }
        return null;
    }

    static boolean sequenceExists(String terminal) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM InvoiceSequence WHERE TerminalID = ?")) {
            ps.setString(1, terminal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static void deleteSequence(String terminal) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM InvoiceSequence WHERE TerminalID = ?")) {
            ps.setString(1, terminal);
            ps.executeUpdate();
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
}
