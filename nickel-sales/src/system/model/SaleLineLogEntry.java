package system.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of a completed sale that was an open item (F2) or had its price edited (F4), as the
 * "Open Items &amp; Price Edits" tab shows it. Read from POS's SaleLine and Sale tables (never written here).
 *
 * @param productId     a catalog ID such as P-0001, or an open item's OPEN-... ID
 * @param unitPrice     the price charged per unit, VAT included
 * @param originalPrice the price before the cashier's edit; null if the price was not edited
 * @param cashierUserId the cashier who rang up the sale
 */
public record SaleLineLogEntry(String saleId, String invoiceNo, Instant completedAt, String cashierUserId,
                               int lineNo, String productId, String productName, String unit,
                               BigDecimal quantity, BigDecimal unitPrice, BigDecimal originalPrice,
                               BigDecimal lineTotal, boolean vatable) {

    /** Filter codes. */
    public static final String OPEN_ITEM = "OPEN_ITEM";
    public static final String PRICE_EDIT = "PRICE_EDIT";

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

    /** "Open Item", "Price Edit", or "Open Item, Price Edit" when both. */
    public String typeLabel() {
        if (openItem() && priceEdited()) {
            return "Open Item, Price Edit";
        }
        return openItem() ? "Open Item" : "Price Edit";
    }

    /** New price minus the original (negative = sold cheaper); null when the price was not edited. */
    public BigDecimal priceChange() {
        return originalPrice == null ? null : unitPrice.subtract(originalPrice);
    }
}
