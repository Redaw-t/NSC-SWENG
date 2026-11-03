package product.repository;

import product.model.Supplier;

import java.util.List;
import java.util.Optional;

/**
 * What the Supplier service needs stored and loaded. Implemented by
 * {@link MySqlSupplierRepository}. Database problems are thrown as RuntimeException.
 */
public interface SupplierRepository {

    /** All suppliers sorted by name; {@code activeOnly} leaves out deactivated ones. */
    List<Supplier> findAll(boolean activeOnly);

    Optional<Supplier> findById(int supplierId);

    /** Exact name, ignoring upper/lower case. */
    Optional<Supplier> findByName(String name);

    /**
     * Saves a new supplier and returns it with its new ID.
     *
     * @throws DuplicateValueException the name is already used
     */
    Supplier insert(Supplier supplier);

    /**
     * Changes every field of an existing supplier.
     *
     * @throws DuplicateValueException the name belongs to another supplier
     * @throws RuntimeException        there is no such supplier
     */
    void update(Supplier supplier);
}
