package reports.model;

/** How the Top Products report orders the products. */
public enum ProductRanking {
    BY_QUANTITY("By quantity sold"),
    BY_AMOUNT("By sales amount");

    private final String label;

    ProductRanking(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
