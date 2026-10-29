package pos.receipt;

import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import shared.Money;
import shared.PhTime;
import shared.Quantity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A receipt for one completed sale (Receipt in the class diagram), built by
 * {@link ReceiptBuilder} from the SAVED sale, so it can only show what was
 * saved. {@link #toText(int)} lays it out as fixed-width lines; the on-screen
 * preview and the printer both use exactly these lines.
 *
 * Amounts are in pesos without the sign (receipt printer fonts may not have
 * the peso sign). Item prices are before VAT; VAT is added under Subtotal. Not BIR-accredited: every receipt says it is not an
 * official receipt.
 *
 * @param cashierName the cashier's name at the time the receipt is made
 * @param reprint     true for a copy printed later (marked REPRINT)
 */
public record Receipt(StoreInfo store, SaleRecord sale, String cashierName, boolean reprint) {

    public static final int DEFAULT_WIDTH = 32;   // characters per line on 80 mm paper
    public static final int MIN_WIDTH = 32;       // 58 mm paper
    public static final int MAX_WIDTH = 58;

    public static final String NOT_OFFICIAL = "THIS IS NOT AN OFFICIAL RECEIPT";

    public Receipt {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sale, "sale");
        cashierName = (cashierName == null || cashierName.isBlank()) ? sale.cashierUserId() : cashierName.trim();
    }

    /** Diagram fields, for convenience. */
    public String invoiceNo() {
        return sale.invoiceNo();
    }

    /** The receipt as lines of at most {@code width} characters. */
    public List<String> toText(int width) {
        if (width < MIN_WIDTH || width > MAX_WIDTH) {
            throw new IllegalArgumentException("Receipt width must be " + MIN_WIDTH + "-" + MAX_WIDTH);
        }
        Lines out = new Lines(width);
        out.center(store.name());
        out.centerWrapped(store.address());
        if (!store.tin().isEmpty()) {
            out.centerWrapped("TIN " + store.tin());
        }
        if (!store.phone().isEmpty()) {
            out.centerWrapped("Tel. " + store.phone());
        }
        if (reprint) {
            out.blank();
            out.center("*** REPRINT ***");
        }
        out.rule();
        out.pair("Invoice", sale.invoiceNo());
        out.pair("Date", PhTime.format(sale.completedAt()));
        out.pair("Terminal", sale.terminalId());
        out.pairWrapped("Cashier", cashierName);
        out.rule();

        boolean wholesale = false;
        boolean exempt = false;
        for (SaleLineRecord line : sale.lines()) {
            out.wrapped(line.productName());
            String flags = (line.priceType() == PriceType.WHOLESALE ? "W" : "") + (line.vatable() ? "" : "E");
            wholesale |= line.priceType() == PriceType.WHOLESALE;
            exempt |= !line.vatable();
            String detail = "  " + Quantity.format(line.quantity()) + " " + line.unit() + " x "
                    + Money.format(line.unitPrice()) + (flags.isEmpty() ? "" : " " + flags);
            out.pair(detail, Money.format(line.lineTotal()));
        }
        out.rule();
        out.pair("Subtotal", Money.format(sale.subtotal()));
        out.pair("  VATable sales", Money.format(sale.vatableSales()));
        out.pair("  VAT-exempt sales", Money.format(sale.vatExemptSales()));
        out.pair("VAT (12%)", Money.format(sale.vatAmount()));
        out.pair("TOTAL", Money.format(sale.total()));
        out.rule();
        for (PaymentRecord p : sale.payments()) {
            if (p.method() == PaymentMethod.CASH) {
                out.pair("Cash", Money.format(p.amountTendered()));
                out.pair("Change", Money.format(p.change()));
            } else {
                out.pair(label(p.method()), Money.format(p.amountApplied()));
                out.pairWrapped("Ref. no.", p.referenceNo());
            }
        }
        out.rule();
        out.pair("Lines", String.valueOf(sale.lines().size()));
        out.wrapped("Prices are before VAT.");
        if (wholesale || exempt) {
            out.wrapped((wholesale ? "W = wholesale price" : "") + (wholesale && exempt ? ", " : "")
                    + (exempt ? "E = VAT-exempt" : ""));
        }
        out.blank();
        out.centerWrapped(NOT_OFFICIAL);
        out.center("Thank you!");
        return out.lines;
    }

    private static String label(PaymentMethod method) {
        return switch (method) {
            case CASH -> "Cash";
            case GCASH -> "GCash";
            case MAYA -> "Maya";
            case QRPH -> "QRPh";
        };
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
                lines.add(" ".repeat(Math.max(0, width - right.length())) + right);
            }
        }

        /** "Label  value" where a long value wraps under the label. */
        void pairWrapped(String label, String value) {
            String text = value == null ? "" : value;
            if (label.length() + 1 + text.length() <= width) {
                pair(label, text);
            } else {
                lines.add(label);
                for (String part : wrap(text, width - 2)) {
                    lines.add("  " + part);
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
