package pos;

import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.ReturnRecord;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.receipt.Receipt;
import pos.receipt.ReceiptBuilder;
import pos.receipt.ReceiptRenderer;
import pos.receipt.StoreInfo;
import pos.repository.MySqlReturnRepository;
import pos.repository.MySqlSaleRepository;
import pos.returns.ReturnException;
import pos.returns.ReturnService;
import shared.Database;
import shared.Money;
import stubs.StubStockRecorder;
import stubs.TestCatalog;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SELF-TEST for POS voids (Void Item / Void Transaction on a retrieved sale): return numbers
 * &lt;invoice&gt;-01, -02 ..., quantities never voided twice, VAT adding up to zero after a full
 * void, the RETURN slip, and the reprinted invoice listing the voids and refunds. With MySQL, a
 * sale is saved and voided for real: the Return / ReturnLine rows and the stock going back.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" pos.ReturnCheck
 */
public class ReturnCheck {

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    static final StoreInfo STORE = new StoreInfo("Nickel Sales Center", "123 Rizal Ave., Manila",
            "000-123-456-000", "(02) 8123 4567");
    static final String TERMINAL = "T97";
    static final String CASHIER = "NSC-00002";
    static final pos.checkout.PaymentService PAY = new pos.checkout.PaymentService();

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - POS void / return checks");
        System.out.println("=================================================================");

        ReturnService service = new ReturnService(new MySqlReturnRepository(), new StubStockRecorder(),
                (e, u) -> { }, "T02");
        SaleRecord sale = sale("T01-2026-000001",
                line(1, "P-0002", "STABILO BOSS (ORANGE)", "pc", "3", "15.00", true),
                line(2, "P-0004", "Rice (per kg)", "kg", "1.5", "52.50", false));

        check("Return numbers are <invoice>-01, then -02", () -> {
            expectText(ReturnRecord.numberFor("T01-2026-000001", 1), "T01-2026-000001-01");
            expectText(ReturnRecord.numberFor("T01-2026-000001", 2), "T01-2026-000001-02");
            expectTrue(ReturnRecord.isReturnNo("T01-2026-000001-01"), "a return number is recognised");
            expectTrue(ReturnRecord.isReturnNo("t01-2026-000001-12 "), "case and spaces don't matter");
            expectTrue(!ReturnRecord.isReturnNo("T01-2026-000001"), "an invoice number is not a return number");
            expectTrue(!ReturnRecord.isReturnNo("4800000000019"), "a product barcode is not a return number");
            ReturnRecord first = service.build(sale, List.of(), Map.of(1, q("1")), CASHIER, now());
            ReturnRecord second = service.build(sale, List.of(first), Map.of(1, q("1")), CASHIER, now());
            expectText(first.returnNo(), "T01-2026-000001-01");
            expectText(second.returnNo(), "T01-2026-000001-02");
            expectText(first.saleId(), sale.saleId());
            expectTrue(first.lines().get(0).saleLineNo() == 1, "points at the sale line");
            expectTrue(first.refundMethod() == PaymentMethod.CASH, "a preview is in cash");
            expectTrue(first.terminalId().equals("T02"), "the terminal that voided");
        });

        check("Void Item: one 15.00 item refunds 15.00 = VATable 13.39 + VAT 1.61", () -> {
            ReturnRecord r = service.build(sale, List.of(), Map.of(1, q("1")), CASHIER, now());
            expect(r.total(), "15.00");
            expect(r.vatableSales(), "13.39");
            expect(r.vatAmount(), "1.61");
            expect(r.vatExemptSales(), "0.00");
            expectTrue(!r.fullVoid(), "Void Item is not a full void");
        });

        check("Can't void more than is left, or a line twice", () -> {
            ReturnRecord two = service.build(sale, List.of(), Map.of(1, q("2")), CASHIER, now());
            expectThrows(() -> service.build(sale, List.of(two), Map.of(1, q("2")), CASHIER, now()),
                    ReturnException.class);
            ReturnRecord last = service.build(sale, List.of(two), Map.of(1, q("1")), CASHIER, now());
            expectThrows(() -> service.build(sale, List.of(two, last), Map.of(1, q("1")), CASHIER, now()),
                    ReturnException.class);
            expectThrows(() -> service.build(sale, List.of(), Map.of(9, q("1")), CASHIER, now()),
                    ReturnException.class);
            Map<Integer, BigDecimal> left = ReturnService.remaining(sale, List.of(two));
            expect(left.get(1), "1");
            expect(left.get(2), "1.5");
        });

        check("Void Transaction voids what is left; the sale and its voids add up to zero", () -> {
            ReturnRecord part = service.build(sale, List.of(), Map.of(1, q("1")), CASHIER, now());
            ReturnRecord rest = service.build(sale, List.of(part), null, CASHIER, now());
            expectTrue(rest.fullVoid(), "Void Transaction is a full void");
            expectTrue(rest.lines().size() == 2, "both lines");
            expect(rest.lines().get(0).quantity(), "2");
            expect(part.total().add(rest.total()), sale.total().toPlainString());
            expect(part.vatableSales().add(rest.vatableSales()), sale.vatableSales().toPlainString());
            expect(part.vatAmount().add(rest.vatAmount()), sale.vatAmount().toPlainString());
            expect(part.vatExemptSales().add(rest.vatExemptSales()), sale.vatExemptSales().toPlainString());
            expectThrows(() -> service.build(sale, List.of(part, rest), null, CASHIER, now()), ReturnException.class);
        });

        check("RETURN slip: minus lines, Item Returned, refund, return barcode, not valid for input tax", () -> {
            ReturnRecord r = service.build(sale, List.of(), Map.of(1, q("1")), "NSC-00001", now());
            ReceiptBuilder builder = new ReceiptBuilder(STORE, id -> id.equals(CASHIER) ? "Jerick Cubillan" : "Xan Luo Ke");
            Receipt slip = builder.buildReturn(sale, r, false);
            List<String> text = slip.toText(42);
            String all = String.join("\n", text);
            String flat = all.replaceAll("\\s+", " ");   // long lines wrap
            expectTrue(text.stream().anyMatch(l -> l.trim().equals("RETURN")), "RETURN title\n" + all);
            expectTrue(all.contains("Return #") && all.contains("T01-2026-000001-01"), "return number");
            expectTrue(all.contains("Jerick Cubillan") && all.contains("Xan Luo Ke"), "both cashiers");
            expectTrue(all.contains("- 1 pc x 15.00"), "minus quantity\n" + all);
            expectTrue(all.contains("- PHP 15.00 V"), "minus amount");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("Item Returned") && l.trim().endsWith("1")), "count");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("TOTAL") && l.endsWith("- PHP 15.00")), "total");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("VATable (V)") && l.endsWith("- 13.39")), "VATable");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("VAT ") && l.endsWith("- 1.61")), "VAT");
            expectTrue(flat.contains(Receipt.RETURN_CLOSING), "closing note");
            expectText(slip.documentNo(), "T01-2026-000001-01");
            expectTrue(slip.lines().stream().anyMatch(l -> l instanceof Receipt.Barcode b
                    && b.value().equals("T01202600000101") && b.label().equals("T01-2026-000001-01")),
                    "barcode holds the number without dashes, printed with dashes under it");
            // short enough for 2-dot bars on 58 mm paper (368 usable dots), like the invoice barcode
            expectTrue(barcode.service.Code128.moduleCount("T01202600000101") * 2 <= 368, "fits at 2 dots");
            expectTrue(ReturnRecord.mayBeReturnBarcode("T01202600000101"), "the barcode value is recognised");
            expectTrue(!ReturnRecord.mayBeReturnBarcode("4800000000019"), "a product barcode is not");
            expectTrue(text.stream().filter(l -> l.trim().equals("T01-2026-000001-01")).count() == 1,
                    "the return number printed under the barcode");
            expectTrue(ReceiptRenderer.render(slip, 32).getHeight() > 0, "renders on 58 mm");
        });

        check("Reprinted invoice lists the voids below the items, every refund, and the net totals", () -> {
            ReturnRecord r1 = service.build(sale, List.of(), Map.of(1, q("1")), CASHIER, now());
            ReturnRecord r2 = service.build(sale, List.of(r1), null, CASHIER, now());
            ReceiptBuilder builder = new ReceiptBuilder(STORE, id -> "Jerick Cubillan", id -> List.of(r1, r2));
            List<String> text = builder.buildReprint(sale).toText(48);
            String all = String.join("\n", text);
            String flat = all.replaceAll("\\s+", " ");   // long lines wrap
            expectTrue(flat.contains("- 1 pc x 15.00 T01-2026-000001-01"), "first void line\n" + all);
            expectTrue(flat.contains("- 2 pc x 15.00 T01-2026-000001-02"), "second void line");
            expectTrue(text.stream().filter(l -> l.startsWith("Refund Method")).count() == 2, "two refunds");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("TOTAL") && l.endsWith("PHP 0.00")), "net total 0");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("Item Count") && l.trim().endsWith(" 0")), "net count");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("VATable (V)") && l.endsWith(" 0.00")), "net VATable");
            expectTrue(text.stream().anyMatch(l -> l.startsWith("VAT ") && l.endsWith(" 0.00")), "net VAT");
            expectTrue(all.contains(Receipt.CLOSING), "still the sales invoice");
            // the receipt right after the sale has no voids
            expectTrue(!String.join("\n", builder.build(sale).toText(48)).contains("Refund Method"), "new sale: no voids");
        });

        check("Several marked items are refunded as ONE return with one number", () -> {
            ReturnRecord both = service.preview(sale, List.of(), Map.of(1, q("2"), 2, q("0.5")), CASHIER);
            expectTrue(both.lines().size() == 2, "two lines in one return");
            expectText(both.returnNo(), "T01-2026-000001-01");
            expect(both.total(), "56.25");                      // 2 x 15.00 + 0.5 x 52.50
            expect(both.vatExemptSales(), "26.25");
            expectTrue(!both.fullVoid(), "something is still left");
            expectThrows(() -> service.preview(sale, List.of(), Map.of(), CASHIER), ReturnException.class);
            ReturnRecord all = service.preview(sale, List.of(), Map.of(1, q("3"), 2, q("1.5")), CASHIER);
            expectTrue(all.fullVoid(), "marking everything is a full void");
            expect(all.total(), sale.total().toPlainString());
        });

        check("Refund card: any method; online refunds need a reference, shown on the slip and the invoice", () -> {
            PaymentRecord cash = PAY.recordRefund(q("15"), PaymentMethod.CASH, null);
            expect(cash.amountApplied(), "15");
            expect(cash.change(), "0");
            expectThrows(() -> PAY.recordRefund(q("15"), PaymentMethod.MAYA, " "), pos.checkout.CheckoutException.class);
            expectThrows(() -> PAY.recordRefund(q("0"), PaymentMethod.CASH, null), pos.checkout.CheckoutException.class);
            PaymentRecord maya = PAY.recordRefund(q("15"), PaymentMethod.MAYA, "MY-998877");
            ReturnRecord r = service.build(sale, List.of(), Map.of(1, q("1")), CASHIER, now(), maya);
            expectTrue(r.refundMethod() == PaymentMethod.MAYA, "refunded by Maya though paid in cash");
            ReceiptBuilder builder = new ReceiptBuilder(STORE, id -> "Jerick Cubillan", id -> List.of(r));
            for (Receipt receipt : List.of(builder.buildReturn(sale, r, false), builder.buildReprint(sale))) {
                List<String> text = receipt.toText(42);
                expectTrue(text.stream().anyMatch(l -> l.startsWith("MAYA") && l.endsWith("- PHP 15.00")),
                        "refund method line\n" + String.join("\n", text));
                expectTrue(text.stream().anyMatch(l -> l.startsWith("Ref. No.") && l.endsWith("MY-998877")),
                        "refund reference");
            }
        });

        boolean db = databaseReachable();
        check("MySQL: void saves Return + ReturnLine, numbers -01/-02, puts stock back, never twice", db, () -> {
            cleanUp();
            SaleRecord saved = sale("T97-2026-000001",
                    line(1, "P-0002", "Ballpen Black", "pc", "3", "12.00", true));
            Database.inTransaction(tx -> {
                new MySqlSaleRepository().save(saved, tx);
                return null;
            });
            BigDecimal before = stockOf("P-0002");
            // a refund for the wrong amount is refused (the sale changed since the card opened)
            expectThrows(() -> service.voidItem(saved, 1, q("1"), CASHIER, PAY.recordRefund(q("5"), PaymentMethod.CASH, null)),
                    ReturnException.class);
            ReturnRecord one = service.voidItem(saved, 1, q("1"), CASHIER,
                    PAY.recordRefund(q("12"), PaymentMethod.CASH, null));
            // paid in cash, refunded by GCash
            ReturnRecord rest = service.voidTransaction(saved, CASHIER,
                    PAY.recordRefund(q("24"), PaymentMethod.GCASH, "GC-1234567"));
            expectText(one.returnNo(), "T97-2026-000001-01");
            expectText(rest.returnNo(), "T97-2026-000001-02");
            expect(stockOf("P-0002").subtract(before), "3");
            List<ReturnRecord> back = new MySqlReturnRepository().findBySaleId(saved.saleId());
            expectTrue(back.size() == 2 && back.get(0).returnNo().equals(one.returnNo()), "read back in order");
            expect(back.get(1).lines().get(0).quantity(), "2");
            expectTrue(back.get(1).fullVoid(), "full void flag saved");
            expectTrue(back.get(0).refundMethod() == PaymentMethod.CASH && back.get(0).refundReference() == null,
                    "cash refund saved");
            expectTrue(back.get(1).refundMethod() == PaymentMethod.GCASH
                    && "GC-1234567".equals(back.get(1).refundReference()), "GCash refund + reference saved");
            expectTrue(new MySqlReturnRepository().findByReturnNo(one.returnNo()).isPresent(), "by return no.");
            expectText(new MySqlReturnRepository().findByReturnNo("T97202600000102").map(ReturnRecord::returnNo)
                    .orElse("none"), "T97-2026-000001-02");   // as scanned from the slip's barcode
            expectThrows(() -> service.voidItem(saved, 1, q("1"), CASHIER,
                    PAY.recordRefund(q("12"), PaymentMethod.CASH, null)), ReturnException.class);
        });
        if (db) {
            cleanUp();
        }

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    static SaleRecord sale(String invoiceNo, SaleLineRecord... lines) {
        BigDecimal vatableWithVat = Money.ZERO;
        BigDecimal exempt = Money.ZERO;
        for (SaleLineRecord l : lines) {
            if (l.vatable()) {
                vatableWithVat = vatableWithVat.add(l.lineTotal());
            } else {
                exempt = exempt.add(l.lineTotal());
            }
        }
        BigDecimal vatable = pos.cart.Vat.netOf(vatableWithVat);
        BigDecimal total = vatableWithVat.add(exempt);
        return new SaleRecord(UUID.randomUUID().toString(), invoiceNo, invoiceNo.substring(0, 3), CASHIER,
                now(), List.of(lines), List.of(new PaymentRecord(1, PaymentMethod.CASH, null, total, total, Money.ZERO)),
                vatable, exempt, vatableWithVat.subtract(vatable), total);
    }

    static SaleLineRecord line(int no, String id, String name, String unit, String qty, String price, boolean vatable) {
        BigDecimal p = new BigDecimal(price);
        return new SaleLineRecord(no, id, null, name, unit, q(qty), p, PriceType.RETAIL, vatable,
                SaleLineRecord.totalOf(q(qty), p));
    }

    static BigDecimal q(String s) {
        return new BigDecimal(s);
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    static BigDecimal stockOf(String productId) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT QuantityOnHand FROM Inventory WHERE ProductID = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    static boolean databaseReachable() {
        try (Connection c = Database.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    static void cleanUp() {
        String sub = "SELECT SaleID FROM Sale WHERE TerminalID = ?";
        try (Connection c = Database.getConnection()) {
            List<String> sqls = new ArrayList<>(List.of(
                    "DELETE FROM ReturnLine WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM `Return` WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM Payment WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM SaleLine WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM Sale WHERE TerminalID = ?"));
            for (String sql : sqls) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, TERMINAL);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.out.println(" [WARN] could not clean up test data for " + TERMINAL + ": " + e);
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
            System.out.println(" [SKIP] " + name + " (no database)");
            return;
        }
        try {
            TestCatalog.resetIfAvailable();
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        } finally {
            TestCatalog.resetIfAvailable();
        }
    }

    static void expect(BigDecimal actual, String expected) {
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
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
