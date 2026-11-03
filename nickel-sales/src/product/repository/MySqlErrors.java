package product.repository;

import java.sql.SQLException;

/** Turns a SQLException into the right RuntimeException for the product repositories. */
final class MySqlErrors {

    /** MySQL error code for "Duplicate entry ... for key ...". */
    private static final int DUPLICATE_ENTRY = 1062;

    private MySqlErrors() { }

    /**
     * A unique-index violation becomes {@link DuplicateValueException}; anything else a plain
     * RuntimeException carrying the SQLException (screens show it through shared.UiErrors).
     */
    static RuntimeException translate(SQLException e, String message) {
        if (e.getErrorCode() == DUPLICATE_ENTRY) {
            return new DuplicateValueException(keyOf(e.getMessage()), e);
        }
        return new RuntimeException(message, e);
    }

    /** "Duplicate entry 'x' for key 'Product.uq_product_barcode'" gives "Product.uq_product_barcode". */
    static String keyOf(String message) {
        if (message == null) {
            return "";
        }
        String marker = "for key '";
        int start = message.lastIndexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = message.indexOf('\'', start);
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }
}
