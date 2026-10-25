package pos.contract;

import java.util.List;
import java.util.Optional;

/**
 * CONTRACT — implemented by the Product &amp; Inventory module, used by POS.
 *
 * POS finds products ONLY through this interface; it never reads the product
 * tables directly. Until the real implementation exists, POS runs against a
 * stand-in (test/pos/stub/StubInventory).
 *
 * Database problems are reported as RuntimeException (as MySqlUserRepository
 * does); "not found" is an empty Optional / empty list, never an exception.
 */
public interface ProductLookup {

    /**
     * Exact match on the barcode a scanner reads. Inactive products are
     * returned too (POS shows "no longer sold" rather than "not found").
     */
    Optional<ProductInfo> findByBarcode(String barcode);

    /** Exact match on the product ID. */
    Optional<ProductInfo> findById(String productId);

    /**
     * Products whose name (or barcode) contains {@code text}, ignoring case,
     * active ones only, sorted by name, at most {@code limit} results.
     * Used by the cashier's search box.
     */
    List<ProductInfo> search(String text, int limit);
}
