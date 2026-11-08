package system.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of a completed sale that was an open item (F2) or had its price edited (F4), as the
 * "Open Items &amp; Price Edits" tab shows it, or a VOID of such a line (POS Void Item / Void
 * Transaction). Read from POS's SaleLine / Sale and ReturnLine / Return tables (never written here).
 *
 * @param productId     a catalog ID such as P-0001, or an open item's OPEN-... ID
 * @param completedAt   when the sale was completed; for a void, when it was voided
 * @param quantity      negative for a void
 * @param unitPrice     the price charged per unit, VAT included
 * @param originalPrice the price before the cashier's edit; null if the price was not edited
 * @param lineTotal     negative for a void (the amount refunded)
 * @param cashierUserId the cashier who rang up the sale; for a void, who voided it
 * @param returnNo      the return number (e.g. T01-2026-000123-01) for a void; null for the sale's own line
 */
public record SaleLineLogEntry(String saleId, String invoiceNo, Instant completedAt, String cashierUserId,
                               int lineNo, String productId, String productName, String unit,
                               BigDecimal quantity, BigDecimal unitPrice, BigDecimal originalPrice,
                               BigDecimal lineTotal, boolean vatable, String returnNo) {

    /** Filter codes. */
    public static final String OPEN_ITEM = "OPEN_ITEM";
    public static final String PRICE_EDIT = "PRICE_EDIT";
    /** Only voids of open items / price-edited lines. */
    public static final String VOIDED = "VOIDED";

    /** A sale's own line (not a void). */
    public SaleLineLogEntry(String saleId, String invoiceNo, Instant completedAt, String cashierUserId,
                            int lineNo, String productId, String productName, String unit,
                            BigDecimal quantity, BigDecimal unitPrice, BigDecimal originalPrice,
                            BigDecimal lineTotal, boolean vatable) {
        this(saleId, invoiceNo, completedAt, cashierUserId, lineNo, productId, productName, unit, quantity,
                unitPrice, originalPrice, lineTotal, vatable, null);
    }

    /** True for a void of the line (it was returned and refunded). */
    public boolean voided() {
        return returnNo != null;
    }

    /** The number to show: the return number for a void, else the invoice number. */
    public String documentNo() {
        return returnNo != null ? returnNo : invoiceNo;
    }

    public SaleLineLogEntry {
        Objects.requireNonNull(invoiceNo, "invoiceNo");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(productName, "productName");
    }

    public boolean openItem() {
        return pos.contract.SaleLineRecord.isOpenItem(productId);
    }

    public boolean priceEdited() {
        return originalPrice != null;
    }

    /** "Open Item", "Price Edit", or "Open Item, Price Edit" when both; "... (Voided)" for a void. */
    public String typeLabel() {
        String type = openItem() && priceEdited() ? "Open Item, Price Edit" : openItem() ? "Open Item" : "Price Edit";
        return voided() ? type + " (Voided)" : type;
    }

    /** New price minus the original (negative = sold cheaper); null when the price was not edited. */
    public BigDecimal priceChange() {
        return originalPrice == null ? null : unitPrice.subtract(originalPrice);
    }
}
