package pos;

import barcode.service.Code128;
import barcode.service.EscPosBarcode;
import pos.cart.PricingService;
import pos.cart.Sale;
import pos.cart.SaleLineItem;
import pos.cart.SaleTotals;
import pos.cart.SalesService;
import pos.checkout.CheckoutService;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.receipt.Receipt;
import pos.receipt.EscPosReceiptPrinter;
import pos.receipt.ReceiptBuilder;
import pos.receipt.ReceiptRenderer;
import pos.receipt.StoreInfo;
import pos.repository.InvoiceNumberGenerator;
import pos.repository.MySqlSaleRepository;
import product.service.ProductService;
import stubs.StubStockRecorder;
import stubs.TestCatalog;
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
 * printed line totals must add up to the printed total (prices include VAT).
 * The printed picture is made from the same lines (pos.receipt.ReceiptRenderer). With MySQL, a sale is
 * saved, reprinted from the database, and the reprint must match the
 * original line for line (only the REPRINT mark differs).
 *
 * (Printing itself needs a printer and a screen; the printer uses exactly
 * these lines, see pos.receipt.EscPosReceiptPrinter.)
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

        check("Every amount on the receipt matches the saved sale (prices include VAT)", () -> {
            Receipt r = new Receipt(STORE, cashSale, "Juan Dela Cruz", false);
            List<String> text = r.toText(Receipt.DEFAULT_WIDTH);
            BigDecimal printedSum = Money.ZERO;
            for (SaleLineRecord line : cashSale.lines()) {
                String detail = "  " + Quantity.format(line.quantity()) + " " + line.unit() + " x "
                        + Money.format(line.unitPrice()) + (line.priceType() == PriceType.WHOLESALE ? " W" : "");
                String row = only(text, l -> l.startsWith(detail + " "), "line for " + line.productName());
                expectTrue(row.endsWith(line.vatable() ? " V" : " E"), "V / E flag: " + row);
                BigDecimal amount = amountBeforeFlag(row);
                expect(amount, line.lineTotal().toPlainString());
                printedSum = printedSum.add(amount);
                expectTrue(text.get(text.indexOf(row) - 1).equals(line.productName()), "name above its amounts");
            }
            // VAT is INSIDE the prices: the lines add up to the TOTAL, and nothing is added on top
            BigDecimal printedSubtotal = amountAtEnd(only(text, l -> l.startsWith("Subtotal"), "Subtotal"));
            BigDecimal printedTotal = amountAtEnd(only(text, l -> l.startsWith("TOTAL"), "TOTAL"));
            BigDecimal vatable = amountAtEnd(only(text, l -> l.startsWith("VATable (V)"), "VATable"));
            BigDecimal vat = amountAtEnd(only(text, l -> l.startsWith("VAT ") && !l.startsWith("VATable"), "VAT"));
            BigDecimal exempt = amountAtEnd(only(text, l -> l.startsWith("VAT-Exempt (E)"), "exempt"));
            expectTrue(printedSum.compareTo(printedTotal) == 0, "printed lines add up to the printed total");
            expectTrue(printedSubtotal.compareTo(printedTotal) == 0, "subtotal = total (VAT is not added)");
            expectTrue(vatable.add(vat).add(exempt).compareTo(printedTotal) == 0, "VATable + VAT + exempt = total");
            expect(printedTotal, "638.75");
            expect(vatable, "500.00");                              // 560.00 / 1.12
            expect(vat, "60.00");
            expect(exempt, "78.75");
            expect(amountAtEnd(only(text, l -> l.startsWith("VAT-Zero (Z)"), "zero-rated")), "0.00");
            expect(printedTotal, cashSale.total().toPlainString());
            expect(amountAtEnd(only(text, l -> l.startsWith("CASH "), "Cash")), "1000.00");
            expect(amountAtEnd(only(text, l -> l.startsWith("CHANGE"), "Change")), "361.25");
        });

        check("The 15.00 example: VATable 13.39 and VAT 1.61 inside the price", () -> {
            SaleRecord one = sale(PaymentMethod.CASH, "15", null,
                    line("P-1", "STABILO BOSS (ORANGE)", "pc", "1", "15.00", PriceType.RETAIL, true));
            List<String> text = new Receipt(STORE, one, "Juan", false).toText(32);
            expect(amountAtEnd(only(text, l -> l.startsWith("TOTAL"), "TOTAL")), "15.00");
            expect(amountAtEnd(only(text, l -> l.startsWith("VATable (V)"), "VATable")), "13.39");
            expect(amountAtEnd(only(text, l -> l.startsWith("VAT ") && !l.startsWith("VATable"), "VAT")), "1.61");
            expect(amountAtEnd(only(text, l -> l.startsWith("CHANGE"), "Change")), "0.00");
            expectTrue(text.stream().noneMatch(l -> l.equals("Wholesale (W)")), "no wholesale legend without W items");
        });

        check("Header and details: store, proprietor, VAT-REG TIN, invoice, PH date and time, terminal, cashier", () -> {
            StoreInfo store = new StoreInfo("Nickel Sales Center", "123 Rizal Ave., Manila", "Jerick Chua Cubillan",
                    "000-123-456-000", "(02) 8123 4567");
            List<String> text = new Receipt(store, cashSale, "Juan Dela Cruz", false).toText(48);
            expectText(text.get(0).trim(), "Nickel Sales Center");
            expectTrue(text.get(1).trim().equals("123 Rizal Ave., Manila"), "address");
            expectTrue(text.contains(center("Jerick Chua Cubillan - PROPRIETOR", 48)), "proprietor centered");
            expectTrue(text.contains(center("VAT-REG TIN: 000-123-456-000", 48)), "TIN centered");
            expectTrue(text.contains(center("Tel. (02) 8123 4567", 48)), "phone centered");
            only(text, l -> l.trim().equals(Receipt.TITLE), "SALES INVOICE title");
            only(text, l -> l.startsWith("Sales Invoice #") && l.endsWith(cashSale.invoiceNo()), "invoice");
            String when = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", java.util.Locale.US)
                    .format(PhTime.of(cashSale.completedAt()));
            expectText(when, "Oct 24, 2026 3:23 PM");                        // 07:23 UTC = 3:23 PM in PH
            only(text, l -> l.startsWith("Transaction Date & Time") && l.endsWith(when), "PH date and time");
            only(text, l -> l.startsWith("POS Terminal") && l.endsWith("T01"), "terminal");
            only(text, l -> l.startsWith("Cashier") && l.endsWith("Juan Dela Cruz"), "cashier");
            only(text, l -> l.startsWith("Item Count") && l.endsWith("9.5"), "item count (2 + 6 + 1.5)");
            String legend = only(text, l -> l.equals("Wholesale (W)"), "wholesale legend");
            expectTrue(text.indexOf(legend) == text.indexOf(only(text, l -> l.startsWith("Item Count"), "count")) + 1,
                    "legend right after Item Count");
            for (String field : List.of("Name: ", "Address: ", "TIN: ", "Business Style: ")) {
                only(text, l -> l.startsWith(field) && l.endsWith("..."), "fill-in line " + field.trim());
            }
            only(text, l -> l.trim().equals(Receipt.CLOSING), "closing note");
            only(text, l -> l.trim().equals(Receipt.THANKS), "thank you");
            expectTrue(text.stream().noneMatch(l -> l.contains("REPRINT")), "an original is not marked REPRINT");
        });

        check("Blank store details are left off; the default name is used", () -> {
            List<String> text = new Receipt(new StoreInfo(" ", null, "", null), cashSale, "Juan", false).toText(42);
            expectText(text.get(0).trim(), StoreInfo.DEFAULT_NAME);
            expectTrue(text.get(1).startsWith("..."), "rule straight after the name");
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
            expect(amountAtEnd(only(text, l -> l.startsWith("GCASH"), "GCash")), "250.00");   // VAT already inside
            only(text, l -> l.startsWith("Ref. No.") && l.endsWith("1009876543210"), "reference");
            expectTrue(text.stream().noneMatch(l -> l.startsWith("CHANGE")), "no CHANGE line: only cash gives change");
            expectTrue(text.stream().noneMatch(l -> l.startsWith("CASH ")), "no cash line");
        });

        check("Barcode: Code 128 of the invoice number, after the fill-in lines, before the closing note", () -> {
            for (boolean reprint : new boolean[]{false, true}) {
                List<Receipt.Line> lines = new Receipt(STORE, cashSale, "Juan Dela Cruz", reprint).lines();
                List<Receipt.Line> codes = lines.stream().filter(l -> l instanceof Receipt.Barcode).toList();
                expectTrue(codes.size() == 1, "exactly one barcode, found " + codes.size());
                Receipt.Barcode code = (Receipt.Barcode) codes.get(0);
                expectText(code.value(), cashSale.invoiceNo());
                expectText(Code128.decode(Code128.modules(code.value())), cashSale.invoiceNo());
                int at = lines.indexOf(code);
                int lastFillIn = -1;
                int closing = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i) instanceof Receipt.FillIn) {
                        lastFillIn = i;
                    }
                    if (lines.get(i) instanceof Receipt.Center c && c.text().equals(Receipt.CLOSING)) {
                        closing = i;
                    }
                }
                expectTrue(lastFillIn < at && at < closing, "barcode between the fill-in lines and the closing note");
            }
        });

        check("Printed picture: 384 dots on 58 mm, 576 on 80 mm, the same every time, sent as raster strips", () -> {
            Receipt r = new Receipt(STORE, cashSale, "Juan Dela Cruz", false);
            java.awt.image.BufferedImage narrow = ReceiptRenderer.render(r, 32);
            java.awt.image.BufferedImage wide = ReceiptRenderer.render(r, 48);
            expectTrue(narrow.getWidth() == 384 && wide.getWidth() == 576, "paper widths in dots");
            expectTrue(narrow.getHeight() > 600 && narrow.getHeight() < 3000, "a receipt-sized picture: " + narrow.getHeight());
            java.awt.image.BufferedImage again = ReceiptRenderer.render(r, 32);
            for (int y = 0; y < narrow.getHeight(); y += 7) {
                for (int x = 0; x < narrow.getWidth(); x += 3) {
                    expectTrue(narrow.getRGB(x, y) == again.getRGB(x, y), "same picture every time (preview = print)");
                }
            }
            byte[] bytes = EscPosReceiptPrinter.toBytes(narrow);
            expectTrue(bytes[0] == 0x1B && bytes[1] == 0x40, "starts with reset");
            String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            expectTrue(raw.contains("\u001Dv0"), "raster picture (GS v 0)");
            int strips = raw.split("\u001Dv0", -1).length - 1;
            expectTrue(strips == (narrow.getHeight() + 127) / 128, "sent in 128-row strips: " + strips);
        });

        check("Code 128: bars decode back to the text (invoice numbers, letters, digits, odd lengths)", () -> {
            String[] samples = {"T01-2026-000042", "T01-2026-000123", "ABCDEFGH-2026-999999", "T1-2026-000001", "1234",
                    "12345", "123456", "A", "a-b c~", "0000", "T01-2027-1234567"};
            for (String sample : samples) {
                boolean[] bars = Code128.modules(sample);
                expectText(Code128.decode(bars), sample);
                expectTrue(bars[0] && bars[bars.length - 1], "starts and ends with a bar");
            }
            expectTrue(Code128.moduleCount("T01-2026-000123") == 178, "digit runs packed: 178 modules");
            expectTrue(Code128.moduleCount("T01-2026-000123") * 2 <= 384, "2-dot bars fit 58 mm paper");
            // independent reference: "A" = start B + 'A' + check 34 + stop, as published in the Code 128 spec
            StringBuilder bits = new StringBuilder();
            for (boolean bar : Code128.modules("A")) {
                bits.append(bar ? '1' : '0');
            }
            expectText(bits.toString(), "11010010000" + "10100011000" + "10001011000" + "1100011101011");
            expectTrue(!Code128.canEncode("") && !Code128.canEncode(null) && !Code128.canEncode("caf\u00e9"), "rejects empty / non-ASCII");
            expectThrows(() -> Code128.modules("caf\u00e9"), IllegalArgumentException.class);
        });

        check("Printer picture: ESC/POS raster header, size and bars match the barcode", () -> {
            boolean[] bars = Code128.modules(cashSale.invoiceNo());
            int dots = EscPosBarcode.moduleDots(bars.length, 384 - 16, 3);
            expectTrue(dots == 2, "2 dots per bar on 58 mm");
            byte[] b = EscPosBarcode.raster(bars, dots, 80);
            int widthBytes = (bars.length * dots + 7) / 8;
            expectTrue((b[0] & 0xFF) == 0x1D && b[1] == 0x76 && b[2] == 0x30 && b[3] == 0, "GS v 0");
            expectTrue((b[4] & 0xFF) == (widthBytes & 0xFF) && (b[5] & 0xFF) == (widthBytes >> 8), "width bytes");
            expectTrue((b[6] & 0xFF) == 80 && b[7] == 0, "height 80 dots");
            expectTrue(b.length == 8 + widthBytes * 80, "data length");
            boolean[] back = new boolean[bars.length];
            for (int m = 0; m < bars.length; m++) {
                int dot = m * dots;
                back[m] = (b[8 + dot / 8] & (0x80 >> (dot % 8))) != 0;
            }
            expectText(Code128.decode(back), cashSale.invoiceNo());
            expectTrue(EscPosBarcode.moduleDots(400, 368, 3) == 1, "very long number falls back to 1 dot");
        });

        check("Reprints are marked REPRINT; the cashier's ID is used if the name can't be found", () -> {
            ReceiptBuilder builder = new ReceiptBuilder(STORE, id -> {
                throw new RuntimeException("database down");
            });
            List<String> reprint = builder.buildReprint(cashSale).toText(42);
            only(reprint, l -> l.trim().equals(Receipt.REPRINT_MARK), "REPRINT mark");
            only(reprint, l -> l.startsWith("Cashier") && l.endsWith("NSC-00002"), "user ID instead of name");
            expectTrue(new ReceiptBuilder(STORE, id -> null).build(cashSale).cashierName().equals("NSC-00002"),
                    "unknown user -> ID");
        });

        boolean db = databaseReachable();
        String terminal = "RC" + (10000 + (int) (Math.random() * 90000));
        check("Reprint from the database matches the original receipt line for line", db, () -> {
            try {
                ProductService products = TestCatalog.products();
                MySqlSaleRepository repo = new MySqlSaleRepository();
                SalesService pos = new SalesService(products, new PricingService(), terminal,
                        new CheckoutService(repo, new InvoiceNumberGenerator(), new StubStockRecorder(), (e, u) -> { }));
                Sale open = pos.startSale(stubs.TestCatalog.TEST_CASHIER);
                pos.addItem("4800000000019", Quantity.of("2"));
                pos.addItem("4800000000026", Quantity.of("7"));
                pos.addItem("4800000000040", Quantity.of("2.333"));
                SaleRecord saved = pos.finalizeSale(new PaymentService().recordPayment(open, PaymentMethod.CASH,
                        Money.of("1000"), null));

                ReceiptBuilder builder = new ReceiptBuilder(STORE, id -> "Juan Dela Cruz");
                List<String> original = builder.build(saved).toText(42);
                SaleRecord fromDb = repo.findByInvoiceNo(saved.invoiceNo()).orElseThrow();
                List<String> reprint = new ArrayList<>(builder.buildReprint(fromDb).toText(42));
                reprint.removeIf(l -> l.isEmpty() || l.trim().equals(Receipt.REPRINT_MARK));   // the REPRINT mark
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

    /** The amount before the V / E flag of an item line, e.g. "PHP 1,000.00 V" -> 1000.00. */
    static BigDecimal amountBeforeFlag(String line) {
        String[] parts = line.trim().split("\\s+");
        return new BigDecimal(parts[parts.length - 2].replace(",", ""));
    }

    /** The number at the end of a receipt line, e.g. "PHP 1,000.00" -> 1000.00. */
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
            TestCatalog.resetIfAvailable();   // every scenario starts from the seed products...
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        } finally {
            TestCatalog.resetIfAvailable();   // ...and leaves them as it found them
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
