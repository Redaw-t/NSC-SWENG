package system.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One stock change, as the Inventory Logs tab shows it: read from Inventory's stock movement
 * history (never written here).
 *
 * @param movementId  the movement's ID
 * @param productName the product's current name; null if the product row is missing
 * @param type        STOCK_IN / STOCK_OUT / ADJUSTMENT / SALE
 * @param quantity    SIGNED change: +20 for a stock-in, -2 for a sale
 * @param reason      DAMAGE / TRANSFER / COUNT_CORRECTION / OTHER / SALE; may be null
 * @param referenceNo e.g. the invoice number of a sale; may be null
 * @param note        what the person typed; may be null
 * @param performedBy user ID; null for sales
 */
public record InventoryLogEntry(long movementId, Instant createdAt, String productId, String productName,
                                String type, BigDecimal quantity, BigDecimal quantityBefore,
                                BigDecimal quantityAfter, String reason, String referenceNo, String note,
                                String performedBy) {

    public static final String STOCK_IN = "STOCK_IN";
    public static final String STOCK_OUT = "STOCK_OUT";
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String SALE = "SALE";

    public InventoryLogEntry {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(quantity, "quantity");
    }

    /** "Stock In", "Count correction" ...: a stored code as words. */
    public static String label(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        String words = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        String sentence = Character.toUpperCase(words.charAt(0)) + words.substring(1);
        return switch (code) {
            case STOCK_IN -> "Stock In";
            case STOCK_OUT -> "Stock Out";
            default -> sentence;
        };
    }

    /** The product's name, or its ID when the name is not known. */
    public String productLabel() {
        return productName == null ? productId : productName;
    }
}
