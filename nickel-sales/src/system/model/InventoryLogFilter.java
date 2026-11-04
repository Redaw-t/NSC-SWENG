package system.model;

import java.time.LocalDate;

/**
 * What the Inventory Logs tab asks for. Every part is optional (null = no limit on it).
 *
 * @param from    first day, in Philippine time
 * @param to      last day (included), in Philippine time
 * @param type    STOCK_IN / STOCK_OUT / ADJUSTMENT / SALE
 * @param keyword found in the product ID, name or barcode, the reference number or the note
 */
public record InventoryLogFilter(LocalDate from, LocalDate to, String type, String keyword) {

    public static InventoryLogFilter all() {
        return new InventoryLogFilter(null, null, null, null);
    }

    public InventoryLogFilter {
        type = type == null || type.isBlank() ? null : type.trim();
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The From date is after the To date.");
        }
    }
}
