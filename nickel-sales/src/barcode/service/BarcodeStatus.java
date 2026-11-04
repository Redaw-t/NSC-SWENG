package barcode.service;

/** The state of a product's barcode, as the Barcode screen shows it. */
public enum BarcodeStatus {

    ASSIGNED("Assigned"),
    UNASSIGNED("Unassigned"),
    /** The barcode is wrong (bad check digit, spaces, ...) or two products share it. */
    ERROR("Duplicate / Error");

    private final String label;

    BarcodeStatus(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
