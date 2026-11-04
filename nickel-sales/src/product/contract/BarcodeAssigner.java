package product.contract;

import java.util.List;

/**
 * CONTRACT — implemented by the Product module (see product.ui.ProductBarcodes), used by Barcode.
 *
 * Barcode lists products and saves a barcode on one ONLY through this interface; it never
 * touches the product tables. The implementation knows who is logged in (it reads the current
 * user on every call), checks the MANAGE_PRODUCTS permission and writes the audit log, so
 * Barcode passes no user. Messages in {@link BarcodeAssignException} are safe to show as they are.
 * Database problems are reported as RuntimeException, as in {@link ProductLookup}.
 */
public interface BarcodeAssigner {

    /** Every sold (active) product with its last-updated time, sorted by name. */
    List<BarcodeEntry> listEntries() throws BarcodeAssignException;

    /**
     * Saves {@code barcode} on the product, replacing any barcode it has. Nothing else about the
     * product changes.
     *
     * @throws BarcodeAssignException no permission, product gone, barcode used by another product, ...
     */
    void assignBarcode(String productId, String barcode) throws BarcodeAssignException;
}
