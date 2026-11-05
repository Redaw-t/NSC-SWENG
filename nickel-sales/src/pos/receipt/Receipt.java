package pos.receipt;

import barcode.service.Code128;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import shared.Money;
import shared.PhTime;
import shared.Quantity;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A receipt (sales invoice) for one completed sale (Receipt in the class diagram), built by
 * {@link ReceiptBuilder} from the SAVED sale, so it can only show what was saved.
 *
 * <p>{@link #lines()} is WHAT the receipt says, top to bottom: the store header, "SALES INVOICE",
 * the invoice details, the items, totals, payment, change, the VAT breakdown, the customer's
 * fill-in lines, a Code 128 barcode of the invoice number and the closing note. Two things draw it:
 * {@link ReceiptRenderer} (the picture that is printed AND shown in the preview, so they always
 * match) and {@link #toText(int)} (plain fixed-width text, used by the checks).
 *
 * <p>Shelf prices include VAT, so the line totals add up to the TOTAL; the VAT breakdown shows
 * how much of it is VAT. Item flags: V = VATable, E = VAT-exempt, W = wholesale price.
 *
 * @param cashierName the cashier's name at the time the receipt is made
 * @param reprint     true for a copy printed later (marked REPRINT)
 */
public record Receipt(StoreInfo store, SaleRecord sale, String cashierName, boolean reprint) {

    public static final int DEFAULT_WIDTH = 32;   // characters per line on 58 mm paper (42-48 for 80 mm)
    public static final int MIN_WIDTH = 32;       // 58 mm paper
    public static final int MAX_WIDTH = 58;

    public static final String TITLE = "SALES INVOICE";
    public static final String REPRINT_MARK = "** REPRINT **";
    public static final String CLOSING = "THIS SERVES AS YOUR SALES INVOICE";
    public static final String THANKS = "Thank you!";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    public Receipt {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sale, "sale");
        cashierName = (cashierName == null || cashierName.isBlank()) ? sale.cashierUserId() : cashierName.trim();
    }

    /** Diagram fields, for convenience. */
    public String invoiceNo() {
        return sale.invoiceNo();
    }

    /* ------------------------------ what the receipt says ------------------------------ */

    /** How a line of text looks. */
    public enum Style { SMALL, NORMAL, BOLD, LARGE, TOTAL }

    /** One piece of the receipt, top to bottom. */
    public sealed interface Line permits Header, Rule, Gap, Center, Left, Pair, Item, FillIn, Barcode { }

    /** The logo and the store details. */
    public record Header(StoreInfo store) implements Line { }

    /** A dotted line across the paper. */
    public record Rule() implements Line { }

    /** A little empty space. */
    public record Gap() implements Line { }

    /** Centred text. */
    public record Center(String text, Style style) implements Line { }

    /** Text on the left. */
    public record Left(String text, Style style) implements Line { }

    /** Text on the left and a value on the right. */
    public record Pair(String left, String right, Style style) implements Line { }

    /** A sold item: its name, then "6 pc x 95.00 W" on the left and "PHP 570.00 V" on the right. */
    public record Item(String name, String detail, String amount) implements Line { }

    /** "Name: ............" for the customer to fill in. */
    public record FillIn(String label) implements Line { }

    /** A Code 128 barcode of {@code value}, with the value printed under it. */
    public record Barcode(String value) implements Line { }

    /** The receipt, top to bottom. */
    public List<Line> lines() {
        List<Line> out = new ArrayList<>();
        out.add(new Header(store));
        out.add(new Rule());
        out.add(new Center(TITLE, Style.LARGE));
        if (reprint) {
            out.add(new Center(REPRINT_MARK, Style.BOLD));
        }
        out.add(new Gap());
        out.add(new Pair("Sales Invoice #", sale.invoiceNo(), Style.SMALL));
        out.add(new Pair("Transaction Date & Time", WHEN.format(PhTime.of(sale.completedAt())), Style.SMALL));
        out.add(new Pair("POS Terminal", sale.terminalId(), Style.SMALL));
        out.add(new Pair("Cashier", cashierName, Style.SMALL));
        out.add(new Rule());

        boolean wholesale = false;
        BigDecimal pieces = BigDecimal.ZERO;
        for (SaleLineRecord line : sale.lines()) {
            boolean w = line.priceType() == PriceType.WHOLESALE;
            wholesale |= w;
            pieces = pieces.add(line.quantity());
            String detail = Quantity.format(line.quantity()) + " " + line.unit() + " x "
                    + Money.format(line.unitPrice()) + (w ? " W" : "");
            out.add(new Item(line.productName(), detail, peso(line.lineTotal()) + (line.vatable() ? " V" : " E")));
        }
        out.add(new Rule());

        out.add(new Left("Item Count   " + Quantity.format(pieces), Style.NORMAL));
        if (wholesale) {
            out.add(new Left("Wholesale (W)", Style.SMALL));
        }
        out.add(new Gap());
        out.add(new Pair("Subtotal", peso(sale.subtotal()), Style.LARGE));
        out.add(new Pair("TOTAL", peso(sale.total()), Style.TOTAL));
        out.add(new Gap());

        out.add(new Left("Payment Method", Style.NORMAL));
        BigDecimal change = Money.ZERO;
        for (PaymentRecord p : sale.payments()) {
            out.add(new Pair(label(p.method()), peso(p.amountTendered()), Style.NORMAL));
            if (p.method() != PaymentMethod.CASH && p.referenceNo() != null) {
                out.add(new Pair("Ref. No.", p.referenceNo(), Style.SMALL));
            }
            change = change.add(p.change());
        }
        out.add(new Gap());
        out.add(new Pair("CHANGE", peso(change), Style.BOLD));
        out.add(new Rule());

        out.add(new Pair("VATable (V)", Money.format(sale.vatableSales()), Style.SMALL));
        out.add(new Pair("VAT", Money.format(sale.vatAmount()), Style.SMALL));
        out.add(new Pair("VAT-Exempt (E)", Money.format(sale.vatExemptSales()), Style.SMALL));
        out.add(new Pair("VAT-Zero (Z)", Money.format(Money.ZERO), Style.SMALL));
        out.add(new Rule());

        out.add(new FillIn("Name"));
        out.add(new FillIn("Address"));
        out.add(new FillIn("TIN"));
        out.add(new FillIn("Business Style"));
        out.add(new Rule());

        if (Code128.canEncode(sale.invoiceNo())) {
            out.add(new Barcode(sale.invoiceNo()));   // scan this to find the transaction again
        }
        out.add(new Gap());
        out.add(new Center(CLOSING, Style.SMALL));
        out.add(new Center(THANKS, Style.SMALL));
        return out;
    }

    /** "PHP 1,250.00". Receipt fonts may not have the peso sign. */
    static String peso(BigDecimal amount) {
        return "PHP " + Money.format(amount);
    }

    static String label(PaymentMethod method) {
        return switch (method) {
            case CASH -> "CASH";
            case GCASH -> "GCASH";
            case MAYA -> "MAYA";
            case QRPH -> "QRPH";
        };
    }

    /* ------------------------------ as plain text ------------------------------ */

    /**
     * The receipt as fixed-width text lines of at most {@code width} characters (the barcode picture is
     * left out; its number is kept). Same content and order as the printed picture.
     */
    public List<String> toText(int width) {
        if (width < MIN_WIDTH || width > MAX_WIDTH) {
            throw new IllegalArgumentException("Receipt width must be " + MIN_WIDTH + "-" + MAX_WIDTH);
        }
        Lines out = new Lines(width);
        for (Line line : lines()) {
            switch (line) {
                case Header h -> {
                    out.center(h.store().name());
                    out.centerWrapped(h.store().address());
                    if (!h.store().proprietor().isEmpty()) {
                        out.centerWrapped(h.store().proprietor() + " - PROPRIETOR");
                    }
                    if (!h.store().tin().isEmpty()) {
                        out.centerWrapped("VAT-REG TIN: " + h.store().tin());
                    }
                    if (!h.store().phone().isEmpty()) {
                        out.centerWrapped("Tel. " + h.store().phone());
                    }
                }
                case Rule r -> out.rule();
                case Gap g -> out.blank();
                case Center c -> out.center(c.text());
                case Left l -> out.wrapped(l.text());
                case Pair p -> out.pair(p.left(), p.right());
                case Item i -> {
                    out.wrapped(i.name());
                    out.pair("  " + i.detail(), i.amount());
                }
                case FillIn f -> out.fillIn(f.label());
                case Barcode b -> out.center(b.value());
            }
        }
        return out.lines;
    }

    /** Builds fixed-width lines. */
    private static final class Lines {
        final int width;
        final List<String> lines = new ArrayList<>();

        Lines(int width) {
            this.width = width;
        }

        void blank() {
            lines.add("");
        }

        void rule() {
            lines.add(".".repeat(width));
        }

        void fillIn(String label) {
            String start = label + ": ";
            lines.add(start + ".".repeat(Math.max(0, width - start.length())));
        }

        void center(String text) {
            for (String part : wrap(text, width)) {
                int pad = (width - part.length()) / 2;
                lines.add(" ".repeat(Math.max(0, pad)) + part);
            }
        }

        void centerWrapped(String text) {
            if (text != null && !text.isEmpty()) {
                center(text);
            }
        }

        void wrapped(String text) {
            lines.addAll(wrap(text, width));
        }

        /** Left text and right-aligned value; the value drops to its own line if both don't fit. */
        void pair(String left, String right) {
            if (left.length() + 1 + right.length() <= width) {
                lines.add(left + " ".repeat(width - left.length() - right.length()) + right);
            } else {
                lines.addAll(wrap(left, width));
                for (String part : wrap(right, width)) {
                    lines.add(" ".repeat(Math.max(0, width - part.length())) + part);
                }
            }
        }

        /** Word-wraps {@code text} to {@code max} characters; very long words are cut. */
        static List<String> wrap(String text, int max) {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.trim().split("\\s+")) {
                while (word.length() > max) {
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    out.add(word.substring(0, max));
                    word = word.substring(max);
                }
                if (line.length() > 0 && line.length() + 1 + word.length() > max) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            if (line.length() > 0 || out.isEmpty()) {
                out.add(line.toString());
            }
            return out;
        }
    }
}
