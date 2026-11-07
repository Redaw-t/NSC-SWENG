package system.model;

import java.time.LocalDate;

/**
 * What the "Open Items &amp; Price Edits" tab asks for. Every part is optional (null = no limit on it).
 *
 * @param from    first day, in Philippine time
 * @param to      last day (included), in Philippine time
 * @param type    {@link SaleLineLogEntry#OPEN_ITEM} or {@link SaleLineLogEntry#PRICE_EDIT}; null = both
 * @param keyword found in the invoice number, the item's name or its product ID
 */
public record SaleLineLogFilter(LocalDate from, LocalDate to, String type, String keyword) {

    public static SaleLineLogFilter all() {
        return new SaleLineLogFilter(null, null, null, null);
    }

    public SaleLineLogFilter {
        type = type == null || type.isBlank() ? null : type.trim();
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The From date is after the To date.");
        }
    }
}
