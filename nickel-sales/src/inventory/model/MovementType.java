package inventory.model;

/** What kind of change a stock movement is; the label shows in the history's Type column. */
public enum MovementType {
    STOCK_IN("Stock In"),
    STOCK_OUT("Stock Out"),
    ADJUSTMENT("Adjustment"),
    /** Written automatically when POS completes a sale (never entered by hand). */
    SALE("Sale");

    private final String label;

    MovementType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
