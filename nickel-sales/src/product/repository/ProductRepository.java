package product.repository;

import product.model.Product;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the Product service needs stored and loaded for products.
 * The only implementation is {@link MySqlProductRepository}; ProductService
 * knows this interface, never the SQL.
 *
 * Database problems are thrown as RuntimeException (cause: SQLException).
 * A value that must be unique (barcode, product ID) and already exists is
 * reported as {@link DuplicateValueException}.
 *
 * Stock is never written here: {@code stockOnHand} on a Product is read-only,
 * and {@link #insert} / {@link #update} leave the stock column alone.
 */
public interface ProductRepository {

    /** Exact match on the product ID, active or not. */
    Optional<Product> findById(String productId);

    /** Exact match on the barcode, active or not. */
    Optional<Product> findByBarcode(String barcode);

    /**
     * Products whose name or barcode contains {@code text} (ignoring case; blank = all),
     * sorted by name, at most {@code limit} of them.
     *
     * @param activeOnly true to leave out discontinued products
     */
    List<Product> search(String text, boolean activeOnly, int limit);

    /** The next free ID in the "P-0001" sequence (one more than the highest in use). */
    String nextProductId();

    /**
     * Saves a new product with the given stock (new products start at zero).
     *
     * @throws DuplicateValueException the product ID or barcode already exists
     */
    void insert(Product product);

    /**
     * Changes everything about an existing product EXCEPT its stock.
     *
     * @throws DuplicateValueException the barcode belongs to another product
     * @throws RuntimeException        there is no such product
     */
    void update(Product product);

    /**
     * Changes ONLY the barcode of one product (null clears it), so nothing else another terminal
     * just edited is overwritten.
     *
     * @throws DuplicateValueException the barcode belongs to another product
     * @throws RuntimeException        there is no such product
     */
    void updateBarcode(String productId, String barcode);

    /**
     * When each product record last changed (product ID to the stored UpdatedAt), in the local time
     * MySQL shows, with no time zone conversion.
     */
    Map<String, LocalDateTime> lastUpdated();
}
