package pos;

import pos.cart.CartException;
import pos.cart.PricingService;
import pos.cart.Sale;
import pos.cart.SaleLineItem;
import pos.cart.SaleStatus;
import pos.cart.SaleTotals;
import pos.cart.SalesService;
import pos.cart.StockProblem;
import pos.cart.ThresholdPricingRule;
import pos.cart.Vat;
import pos.contract.InsufficientStockException;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.stub.StubInventory;
import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SELF-TEST for POS Phase 1: the cart and pricing engine, run against the
 * StubInventory stand-in (no database, no screen).
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" pos.CartCheck
 * (macOS/Linux: ':' instead of ';'). Exit code 1 on failure.
 *
 * Sample products (see StubInventory):
 *   4800000000019 Bond Paper   250.00 / 235.00   stock 40
 *   4800000000026 Ballpen       12.00 /  10.00   stock 200
 *   4800000000033 Notebook      45.00 / (none)   stock 60
 *   4800000000040 Rice per kg   52.50 /  50.00   VAT-exempt, stock 100.5
 *   4800000000057 Masking Tape  35.75 /  32.00   out of stock
 *   4800000000064 Calculator   499.00 / (none)   no longer sold
 *   (no barcode)  Glue Stick    18.00 /  16.50   P-0007, stock 30
 */
public class CartCheck {

    static final String PAPER = "4800000000019";
    static final String BALLPEN = "4800000000026";
    static final String NOTEBOOK = "4800000000033";
    static final String RICE = "4800000000040";
    static final String TAPE = "4800000000057";
    static final String CALCULATOR = "4800000000064";

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - POS Phase 1 checks (cart and pricing)");
        System.out.println("=================================================================");

        check("Pricing rule: retail below " + ThresholdPricingRule.WHOLESALE_MIN_QTY + ", wholesale from it", () -> {
            expectTrue(ThresholdPricingRule.WHOLESALE_MIN_QTY == 6, "the store's threshold is 6");
            ThresholdPricingRule rule = new ThresholdPricingRule();
            expectTrue(rule.priceType(q("5")) == PriceType.RETAIL, "5 is retail");
            expectTrue(rule.priceType(q("5.999")) == PriceType.RETAIL, "5.999 is retail");
            expectTrue(rule.priceType(q("6")) == PriceType.WHOLESALE, "6 is wholesale");
            ThresholdPricingRule ten = new ThresholdPricingRule(10);
            expectTrue(ten.priceType(q("9")) == PriceType.RETAIL, "threshold can be changed in one place");
        });

        check("Scanning adds a line; scanning the same item again adds to that line", () -> {
            SalesService pos = newSale();
            pos.addItem(PAPER);
            pos.addItem(" " + PAPER + " ");
            Sale sale = pos.currentSale().orElseThrow();
            expectTrue(sale.getItems().size() == 1, "still one line");
            SaleLineItem line = sale.findLine("P-0001").orElseThrow();
            expect(line.quantity(), "2.000");
            expect(line.lineTotal(), "500.00");
            expectTrue(line.priceType() == PriceType.RETAIL, "retail");
        });

        check("5 -> 6 switches the line to wholesale, and back to retail at 5", () -> {
            SalesService pos = newSale();
            SaleLineItem five = pos.addItem(BALLPEN, q("5"));
            expect(five.unitPrice(), "12.00");
            expect(five.lineTotal(), "60.00");
            expectTrue(five.priceType() == PriceType.RETAIL, "5 pens retail");
            SaleLineItem six = pos.addItem(BALLPEN);                     // the 6th scan
            expect(six.unitPrice(), "10.00");
            expect(six.lineTotal(), "60.00");
            expectTrue(six.priceType() == PriceType.WHOLESALE, "6 pens wholesale");
            SaleLineItem back = pos.updateQuantity("P-0002", q("5"));
            expect(back.unitPrice(), "12.00");
            expectTrue(back.priceType() == PriceType.RETAIL, "back to retail at 5");
        });

        check("A product with no wholesale price is always retail", () -> {
            SalesService pos = newSale();
            SaleLineItem ten = pos.addItem(NOTEBOOK, q("10"));
            expect(ten.unitPrice(), "45.00");
            expectTrue(ten.priceType() == PriceType.RETAIL, "recorded as RETAIL, not WHOLESALE");
        });

        check("Decimal quantities for items sold by kg", () -> {
            SalesService pos = newSale();
            SaleLineItem rice = pos.addItem(RICE, q("1.5"));
            expect(rice.lineTotal(), "78.75");                           // 1.5 x 52.50
            rice = pos.updateQuantity("P-0004", q("6.25"));
            expect(rice.unitPrice(), "50.00");                           // wholesale from 6 kg
            expect(rice.lineTotal(), "312.50");
        });

        check("Products without a barcode are added by ID (from search)", () -> {
            SalesService pos = newSale();
            SaleLineItem glue = pos.addProduct("P-0007");
            expectTrue(glue.barcode() == null, "no barcode");
            pos.addProduct("P-0007", q("5"));
            expect(pos.currentSale().orElseThrow().findLine("P-0007").orElseThrow().unitPrice(), "16.50");
        });

        check("Out of stock is blocked with the stock message, and the sale is unchanged", () -> {
            SalesService pos = newSale();
            pos.addItem(PAPER);
            try {
                pos.addItem(TAPE);
                throw new AssertionError("expected InsufficientStockException");
            } catch (InsufficientStockException e) {
                expectText(e.getMessage(), "Not enough stock for Masking Tape 1 inch: 0 left, 1 needed.");
            }
            expectTrue(pos.currentSale().orElseThrow().getItems().size() == 1, "tape was not added");
        });

        check("Raising a quantity above the stock is blocked; the line keeps its old quantity", () -> {
            SalesService pos = newSale();
            pos.addItem(PAPER, q("39"));
            try {
                pos.addItem(PAPER, q("2"));                               // 41 > 40 in stock
                throw new AssertionError("expected InsufficientStockException");
            } catch (InsufficientStockException e) {
                expectText(e.getMessage(), "Not enough stock for Bond Paper A4 (ream): 40 left, 41 needed.");
            }
            expectThrows(() -> pos.updateQuantity("P-0001", q("50")), InsufficientStockException.class);
            expect(pos.currentSale().orElseThrow().findLine("P-0001").orElseThrow().quantity(), "39.000");
            pos.updateQuantity("P-0001", q("40"));                       // exactly the stock is fine
        });

        check("Stock drops after adding: only lowering works, Pay stays off until it fits", () -> {
            StubInventory stock = new StubInventory();
            SalesService pos = new SalesService(stock, "T01");
            pos.startSale("NSC-00002");
            pos.addItem(BALLPEN, q("10"));
            pos.addItem(PAPER);
            expectTrue(pos.canCheckout() && pos.findStockProblems().isEmpty(), "fine at first");

            // the other terminal sells ballpens: only 3 left
            stock.add("P-0002", BALLPEN, "Ballpen Black", "pc", "12.00", "10.00", true, true, "3");
            expectTrue(!pos.canCheckout(), "Pay is off");
            List<StockProblem> problems = pos.findStockProblems();
            expectTrue(problems.size() == 1, "one problem line");
            expectText(problems.get(0).message(),
                    "Not enough stock for Ballpen Black: 3 left, 10 in the sale. Lower the quantity or remove it.");

            expectThrows(() -> pos.updateQuantity("P-0002", q("11")), InsufficientStockException.class);
            expectThrows(() -> pos.addItem(BALLPEN), InsufficientStockException.class);
            expect(pos.updateQuantity("P-0002", q("8")).quantity(), "8.000");     // lowering is allowed...
            expectTrue(!pos.canCheckout(), "...but 8 > 3, so Pay is still off");
            pos.updateQuantity("P-0002", q("3"));
            expectTrue(pos.canCheckout() && pos.findStockProblems().isEmpty(), "3 fits: Pay is back on");

            // removing the line also fixes it
            stock.add("P-0002", BALLPEN, "Ballpen Black", "pc", "12.00", "10.00", true, true, "1");
            expectTrue(!pos.canCheckout(), "off again at 1 left");
            pos.removeItem("P-0002");
            expectTrue(pos.canCheckout(), "on after removing the line");
        });

        check("A product deactivated after it was added blocks Pay until removed", () -> {
            StubInventory stock = new StubInventory();
            SalesService pos = new SalesService(stock, "T01");
            pos.startSale("NSC-00002");
            pos.addItem(NOTEBOOK);
            stock.add("P-0003", NOTEBOOK, "Notebook 80 leaves", "pc", "45.00", null, true, false, "60");
            expectText(pos.findStockProblems().get(0).message(),
                    "Notebook 80 leaves is no longer sold. Remove it from the sale.");
            expectTrue(!pos.canCheckout(), "Pay is off");
            pos.removeItem("P-0003");
            expectTrue(!pos.canCheckout(), "an empty sale can't be paid either");
        });

        check("Products no longer sold, unknown barcodes and bad quantities are refused", () -> {
            SalesService pos = newSale();
            expectMessage(() -> pos.addItem(CALCULATOR), "Desk Calculator is no longer sold.");
            expectMessage(() -> pos.addItem("0000000000000"), "No product has the barcode 0000000000000. "
                    + "Search for it by name, or ask inventory staff to register it.");
            expectMessage(() -> pos.addItem("  "), "Scan or type a barcode.");
            expectMessage(() -> pos.addItem(PAPER, q("0")), "Quantity must be more than 0.");
            expectMessage(() -> pos.addItem(PAPER, q("-2")), "Quantity must be more than 0.");
            pos.addItem(PAPER);
            expectMessage(() -> pos.updateQuantity("P-0001", q("0")), "Quantity must be more than 0.");
            expectMessage(() -> pos.updateQuantity("P-0003", q("1")), "That product is not in this sale.");
            expectTrue(pos.currentSale().orElseThrow().getItems().size() == 1, "nothing else changed");
        });

        check("Remove a line, clear the cart, lines keep their order", () -> {
            SalesService pos = newSale();
            pos.addItem(PAPER);
            pos.addItem(BALLPEN);
            pos.addItem(NOTEBOOK);
            pos.removeItem("P-0002");
            List<SaleLineItem> items = pos.currentSale().orElseThrow().getItems();
            expectText(items.get(0).productId() + "," + items.get(1).productId(), "P-0001,P-0003");
            expectMessage(() -> pos.removeItem("P-0002"), "That product is not in this sale.");
            pos.clearItems();
            expectTrue(pos.currentSale().orElseThrow().isEmpty(), "cart is empty");
            expect(pos.getTotal(), "0.00");
        });

        check("Totals: VATable + VAT + VAT-exempt add up to the total", () -> {
            SalesService pos = newSale();
            pos.addItem(PAPER, q("2"));                                  // 500.00 VATable
            pos.addItem(BALLPEN, q("6"));                                //  60.00 VATable (wholesale)
            pos.addItem(RICE, q("1.5"));                                 //  78.75 VAT-exempt
            SaleTotals t = pos.getTotals();                              // prices are BEFORE VAT
            expect(t.vatableSales(), "560.00");                          // 500.00 + 60.00
            expect(t.vatExemptSales(), "78.75");
            expect(t.subtotal(), "638.75");
            expect(t.vatAmount(), "67.20");                              // 12% of 560.00, added
            expect(t.total(), "705.95");                                 // 638.75 + 67.20
            expectTrue(t.vatableSales().add(t.vatAmount()).add(t.vatExemptSales()).compareTo(t.total()) == 0,
                    "parts add up to the total");
            expect(Vat.on(Money.of("100.00")), "12.00");
            expect(Vat.on(Money.of("223.21")), "26.79");                 // 26.7852 rounds up
            expect(Vat.on(Money.of("0.04")), "0.00");                    // 0.0048 rounds down
            expectTrue(SaleTotals.of(List.of()).equals(SaleTotals.ZERO), "empty cart totals are zero");
        });

        check("Totals add up for every amount from 0.01 to 300.00", () -> {
            for (int cents = 1; cents <= 30000; cents++) {
                BigDecimal price = BigDecimal.valueOf(cents, 2);
                SaleLineItem line = new SaleLineItem("X", null, "X", "pc", Quantity.ONE, price,
                        PriceType.RETAIL, true);
                SaleTotals t = SaleTotals.of(List.of(line));
                if (t.vatableSales().add(t.vatAmount()).compareTo(t.total()) != 0 || Money.isNegative(t.vatAmount())
                        || t.vatableSales().compareTo(price) != 0 || t.vatAmount().compareTo(Vat.on(price)) != 0) {
                    throw new AssertionError("does not add up for " + price);
                }
            }
        });

        check("The cart's lines and totals make a valid SaleRecord (ready for checkout)", () -> {
            SalesService pos = newSale();
            pos.addItem(PAPER, q("3"));
            pos.addItem(BALLPEN, q("7"));
            pos.addItem(RICE, q("2.333"));
            pos.addProduct("P-0007", q("6"));
            Sale sale = pos.currentSale().orElseThrow();
            List<SaleLineRecord> lines = new ArrayList<>();
            for (SaleLineItem item : sale.getItems()) {
                lines.add(item.toRecord(lines.size() + 1));
            }
            SaleTotals t = sale.totals();
            new SaleRecord(sale.getSaleId(), "T01-2026-000001", sale.getTerminalId(), sale.getCashierId(),
                    Instant.now(), lines,
                    List.of(new PaymentRecord(1, PaymentMethod.CASH, null, t.total(), t.total(), Money.ZERO)),
                    t.vatableSales(), t.vatExemptSales(), t.vatAmount(), t.total());   // throws if inconsistent
        });

        check("Starting, cancelling and the one-open-sale rule", () -> {
            SalesService pos = new SalesService(new StubInventory(), new PricingService(), "T01");
            expectTrue(pos.currentSale().isEmpty(), "no sale yet");
            expectThrows(() -> pos.addItem(PAPER), IllegalStateException.class);
            Sale sale = pos.startSale("NSC-00002");
            expectTrue(sale.getStatus() == SaleStatus.OPEN, "open");
            expectText(sale.getCashierId() + "@" + sale.getTerminalId(), "NSC-00002@T01");
            expectThrows(() -> pos.startSale("NSC-00002"), IllegalStateException.class);
            pos.addItem(PAPER);
            pos.cancelSale();
            expectTrue(sale.getStatus() == SaleStatus.CANCELLED, "cancelled");
            expectTrue(pos.currentSale().isEmpty(), "no open sale after cancel");
            expectThrows(() -> pos.getTotal(), IllegalStateException.class);
            Sale next = pos.startSale("NSC-00002");
            expectTrue(next.isEmpty() && !next.getSaleId().equals(sale.getSaleId()), "a fresh sale");
            expectThrows(() -> sale.getItems().add(null), UnsupportedOperationException.class);
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------- helpers ------------------------------- */

    static SalesService newSale() {
        SalesService pos = new SalesService(new StubInventory(), "T01");
        pos.startSale("NSC-00002");
        return pos;
    }

    static BigDecimal q(String quantity) {
        return Quantity.of(quantity);
    }

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

    /** Expects a CartException with exactly this message (what the cashier sees). */
    static void expectMessage(Body body, String message) {
        try {
            body.run();
        } catch (CartException e) {
            expectText(e.getMessage(), message);
            return;
        } catch (Throwable t) {
            throw new AssertionError("expected CartException \"" + message + "\" but got " + t);
        }
        throw new AssertionError("expected CartException \"" + message + "\"");
    }
}
