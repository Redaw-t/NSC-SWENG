package pos;

import pos.cart.PricingService;
import pos.cart.Sale;
import pos.cart.SaleLineItem;
import pos.cart.SaleTotals;
import pos.cart.SalesService;
import pos.checkout.CheckoutService;
import pos.checkout.InvoiceNumberGenerator;
import pos.checkout.MySqlSaleRepository;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.receipt.Receipt;
import pos.receipt.ReceiptBuilder;
import pos.receipt.StoreInfo;
import pos.stub.StubInventory;
import shared.Database;
import shared.Money;
import shared.PhTime;
import shared.Quantity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SELF-TEST for POS Phase 5: receipts.
 *
 * Checks that a receipt shows exactly the saved sale: every amount is read
 * back OUT of the receipt text and compared with the SaleRecord, and the
 * printed line totals must add up to the printed total. With MySQL, a sale is
 * saved, reprinted from the database, and the reprint must match the
 * original line for line (only the REPRINT mark differs).
 *
 * (Printing itself needs a printer and a screen; the printer uses exactly
 * these lines, see pos.receipt.JavaFxReceiptPrinter.)
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" pos.ReceiptCheck
 */
public class ReceiptCheck {

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    static final StoreInfo STORE = new StoreInfo("Nickel Sales Center", "123 Rizal Ave., Manila",
            "000-123-456-000", "(02) 8123 4567");

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - POS Phase 5 checks (receipts)");
        System.out.println("=================================================================");

        SaleRecord cashSale = sale(PaymentMethod.CASH, "1000", null,
                line("P-0001", "Bond Paper A4 (ream)", "ream", "2", "250.00", PriceType.RETAIL, true),
                line("P-0002", "Ballpen Black", "pc", "6", "10.00", PriceType.WHOLESALE, true),
                line("P-0004", "Rice (per kg)", "kg", "1.5", "52.50", PriceType.RETAIL, false));

        check("Every amount on the receipt matches the saved sale", () -> {
            Receipt r = new Receipt(STORE, cashSale, "Juan Dela Cruz", false);
            List<String> text = r.toText(Receipt.DEFAULT_WIDTH);
            BigDecimal printedSum = Money.ZERO;
            for (SaleLineRecord line : cashSale.lines()) {
                String detail = "  " + Quantity.format(line.quantity()) + " " + line.unit() + " x "
                        + Money.format(line.unitPrice());
                String row = only(text, l -> l.startsWith(detail), "line for " + line.productName());
                BigDecimal amount = amountAtEnd(row);
                expect(amount, line.lineTotal().toPlainString());
                printedSum = printedSum.add(amount);
                expectTrue(text.get(text.indexOf(row) - 1).equals(line.productName()), "name above its amounts");
            }
            // prices are before VAT: lines add up to the Subtotal, and Subtotal + VAT = TOTAL
            BigDecimal printedSubtotal = amountAtEnd(only(text, l -> l.startsWith("Subtotal"), "Subtotal"));
            BigDecimal printedVat = amountAtEnd(only(text, l -> l.startsWith("VAT (12%)"), "VAT"));
            BigDecimal printedTotal = amountAtEnd(only(text, l -> l.startsWith("TOTAL"), "TOTAL"));
            expectTrue(printedSum.compareTo(printedSubtotal) == 0, "printed lines add up to the printed subtotal");
            expectTrue(printedSubtotal.add(printedVat).compareTo(printedTotal) == 0, "subtotal + VAT = total");
            expect(printedSubtotal, "638.75");
            expect(amountAtEnd(only(text, l -> l.trim().startsWith("VATable sales"), "VATable")), "560.00");
            expect(amountAtEnd(only(text, l -> l.trim().startsWith("VAT-exempt sales"), "exempt")), "78.75");
            expect(printedVat, "67.20");
            expect(printedTotal, "705.95");
            expect(printedTotal, cashSale.total().toPlainString());
            expect(amountAtEnd(only(text, l -> l.startsWith("Cash "), "Cash")), "1000.00");
            expect(amountAtEnd(only(text, l -> l.startsWith("Change"), "Change")), "294.05");
            only(text, l -> l.equals("Prices are before VAT."), "before-VAT note");
        });

        check("Header: store details, invoice, PH date and time, terminal, cashier", () -> {
            List<String> text = new Receipt(STORE, cashSale, "Juan Dela Cruz", false).toText(42);
            expectText(text.get(0).trim(), "Nickel Sales Center");
            expectTrue(text.get(1).trim().equals("123 Rizal Ave., Manila"), "address");
            expectTrue(text.contains(center("TIN 000-123-456-000", 42)), "TIN centered");
            expectTrue(text.contains(center("Tel. (02) 8123 4567", 42)), "phone centered");
            only(text, l -> l.startsWith("Invoice") && l.endsWith(cashSale.invoiceNo()), "invoice");
            only(text, l -> l.startsWith("Date") && l.endsWith(PhTime.format(cashSale.completedAt())), "PH date");
            only(text, l -> l.startsWith("Terminal") && l.endsWith("T01"), "terminal");
            only(text, l -> l.startsWith("Cashier") && l.endsWith("Juan Dela Cruz"), "cashier");
            only(text, l -> l.trim().equals(Receipt.NOT_OFFICIAL), "not an official receipt");
            only(text, l -> l.equals("W = wholesale price, E = VAT-exempt"), "legend");
            expectTrue(text.stream().noneMatch(l -> l.contains("REPRINT")), "an original is not marked REPRINT");
        });

        check("Blank store details are left off; the default name is used", () -> {
            List<String> text = new Receipt(new StoreInfo(" ", null, "", null), cashSale, "Juan", false).toText(42);
            expectText(text.get(0).trim(), StoreInfo.DEFAULT_NAME);
            expectTrue(text.get(1).startsWith("---"), "rule straight after the name");
        });

        check("No line is wider than the paper (58 mm, 80 mm, wide), long names wrap", () -> {
            SaleRecord longSale = sale(PaymentMethod.CASH, "999999.99", null,
                    line("P-9", "Heavy Duty Industrial Grade Stainless Steel Storage Cabinet With Lock", "pc",
                            "3", "99999.99", PriceType.RETAIL, true),
                    line("P-8", "Supercalifragilisticexpialidociousproductnamewithoutspaces", "kg",
                            "1.125", "1234.56", PriceType.WHOLESALE, false));
            for (int width : new int[] {Receipt.MIN_WIDTH, Receipt.DEFAULT_WIDTH, Receipt.MAX_WIDTH}) {
                List<String> text = new Receipt(STORE, longSale, "Maria Clara de los Santos y Ibarra", false).toText(width);
                for (String l : text) {
                    expectTrue(l.length() <= width, "line wider than " + width + ": [" + l + "]");
                }
                expectTrue(text.stream().anyMatch(l -> l.endsWith(Money.format(longSale.total()))), "total at " + width);
            }
            expectThrows(() -> new Receipt(STORE, longSale, "x", false).toText(20), IllegalArgumentException.class);
        });

        check("GCash / Maya / QRPh receipts show the reference number and no change", () -> {
            SaleRecord g = sale(PaymentMethod.GCASH, null, "1009876543210",
                    line("P-0001", "Bond Paper A4 (ream)", "ream", "1", "250.00", PriceType.RETAIL, true));
            List<String> text = new Receipt(STORE, g, "Juan", false).toText(42);
            expect(amountAtEnd(only(text, l -> l.startsWith("GCash"), "GCash")), "280.00");   // 250.00 + VAT
            only(text, l -> l.startsWith("Ref. no.") && l.endsWith("1009876543210"), "reference");
            expectTrue(text.stream().noneMatch(l -> l.startsWith("Change") || l.startsWith("Cash ")), "no change");
        });

        check("Reprints are marked REPRINT; the cashier's ID is used if the name can't be found", () -> {
            ReceiptBuilder builder = new ReceiptBuilder(STORE, id -> {
                throw new RuntimeException("database down");
            });
            List<String> reprint = builder.buildReprint(cashSale).toText(42);
            only(reprint, l -> l.trim().equals("*** REPRINT ***"), "REPRINT mark");
            only(reprint, l -> l.startsWith("Cashier") && l.endsWith("NSC-00002"), "user ID instead of name");
            expectTrue(new ReceiptBuilder(STORE, id -> null).build(cashSale).cashierName().equals("NSC-00002"),
                    "unknown user -> ID");
        });

        boolean db = databaseReachable();
        String terminal = "RC" + (10000 + (int) (Math.random() * 90000));
        check("Reprint from the database matches the original receipt line for line", db, () -> {
            try {
                StubInventory stock = new StubInventory();
                MySqlSaleRepository repo = new MySqlSaleRepository();
                SalesService pos = new SalesService(stock, new PricingService(), terminal,
                        new CheckoutService(repo, new InvoiceNumberGenerator(), stock, (e, u) -> { }));
                Sale open = pos.startSale("NSC-00002");
                pos.addItem("4800000000019", Quantity.of("2"));
                pos.addItem("4800000000026", Quantity.of("7"));
                pos.addItem("4800000000040", Quantity.of("2.333"));
                SaleRecord saved = pos.finalizeSale(new PaymentService().recordPayment(open, PaymentMethod.CASH,
                        Money.of("1000"), null));

                ReceiptBuilder builder = new ReceiptBuilder(STORE, id -> "Juan Dela Cruz");
                List<String> original = builder.build(saved).toText(42);
                SaleRecord fromDb = repo.findByInvoiceNo(saved.invoiceNo()).orElseThrow();
                List<String> reprint = new ArrayList<>(builder.buildReprint(fromDb).toText(42));
                reprint.removeIf(l -> l.isEmpty() || l.trim().equals("*** REPRINT ***"));   // the REPRINT mark
                List<String> originalNoBlank = new ArrayList<>(original);
                originalNoBlank.removeIf(String::isEmpty);
                expectTrue(reprint.equals(originalNoBlank), "reprint differs from the original:\n"
                        + String.join("\n", original) + "\n---\n" + String.join("\n", reprint));
            } finally {
                cleanUp(terminal);
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

    static SaleLineItem line(String id, String name, String unit, String qty, String price, PriceType type,
                             boolean vatable) {
        return new SaleLineItem(id, null, name, unit, Quantity.of(qty), Money.of(price), type, vatable);
    }

    /** A saved-sale record for these lines, totals worked out like the cart does. */
    static SaleRecord sale(PaymentMethod method, String cash, String reference, SaleLineItem... items) {
        List<SaleLineRecord> lines = new ArrayList<>();
        for (SaleLineItem item : items) {
            lines.add(item.toRecord(lines.size() + 1));
        }
        SaleTotals t = SaleTotals.of(List.of(items));
        PaymentRecord p = method == PaymentMethod.CASH
                ? new PaymentRecord(1, method, null, Money.of(cash), t.total(), Money.of(cash).subtract(t.total()))
                : new PaymentRecord(1, method, reference, t.total(), t.total(), Money.ZERO);
        return new SaleRecord(UUID.randomUUID().toString(), "T01-2026-000042", "T01", "NSC-00002",
                Instant.parse("2026-10-24T07:23:00Z"), lines, List.of(p),
                t.vatableSales(), t.vatExemptSales(), t.vatAmount(), t.total());
    }

    /** The number at the end of a receipt line, e.g. "1,000.00" -> 1000.00. */
    static BigDecimal amountAtEnd(String line) {
        String[] parts = line.trim().split("\\s+");
        return new BigDecimal(parts[parts.length - 1].replace(",", ""));
    }

    static String only(List<String> text, java.util.function.Predicate<String> match, String what) {
        List<String> found = text.stream().filter(match).toList();
        if (found.size() != 1) {
            throw new AssertionError("expected exactly one " + what + " line, found " + found.size()
                    + "\n" + String.join("\n", text));
        }
        return found.get(0);
    }

    static String center(String s, int width) {
        return " ".repeat((width - s.length()) / 2) + s;
    }

    static boolean databaseReachable() {
        try (Connection c = Database.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    static void cleanUp(String terminal) {
        String sub = "SELECT SaleID FROM Sale WHERE TerminalID = ?";
        try (Connection c = Database.getConnection()) {
            for (String sql : List.of(
                    "DELETE FROM Payment WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM SaleLine WHERE SaleID IN (SELECT * FROM (" + sub + ") s)",
                    "DELETE FROM Sale WHERE TerminalID = ?",
                    "DELETE FROM InvoiceSequence WHERE TerminalID = ?")) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, terminal);
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
