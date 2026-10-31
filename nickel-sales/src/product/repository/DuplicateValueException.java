package product.repository;

/**
 * A value that must be unique (a barcode, a category name, ...) is already in
 * the database. Thrown by the repositories when MySQL refuses the row, so the
 * service can turn it into a clear message even when two terminals race.
 */
public class DuplicateValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    /**
     * @param key the name of the unique index that was hit, e.g. "uq_product_barcode" or "PRIMARY"
     */
    public DuplicateValueException(String key, Throwable cause) {
        super("Duplicate value for " + key, cause);
        this.key = key == null ? "" : key;
    }

    /** The unique index that was hit, as MySQL names it (may include the table, e.g. "Product.uq_product_barcode"). */
    public String getKey() {
        return key;
    }

    /** True if the unique index name contains {@code part} (ignoring case), e.g. "barcode". */
    public boolean isKey(String part) {
        return key.toLowerCase().contains(part.toLowerCase());
    }
}
