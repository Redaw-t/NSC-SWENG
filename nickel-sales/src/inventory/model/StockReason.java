package inventory.model;

import java.util.List;

/** The "Reason" dropdowns of the Stock Out and Adjust Stock windows. */
public enum StockReason {
    SALE("Sale"),
    DAMAGE("Damage"),
    TRANSFER("Transfer"),
    COUNT_CORRECTION("Count correction"),
    OTHER("Other");

    private final String label;

    StockReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Reasons offered by Stock Out. */
    public static List<StockReason> forStockOut() {
        return List.of(SALE, DAMAGE, TRANSFER, OTHER);
    }

    /** Reasons offered by Adjust Stock. */
    public static List<StockReason> forAdjustment() {
        return List.of(COUNT_CORRECTION, DAMAGE, OTHER);
    }

    /** From the database value; null stays null. */
    public static StockReason fromDb(String value) {
        return value == null ? null : valueOf(value);
    }
}
