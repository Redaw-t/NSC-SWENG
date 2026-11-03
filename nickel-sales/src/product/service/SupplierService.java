package product.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import auth.permission.AuthorizationService;
import product.model.Supplier;
import product.repository.DuplicateValueException;
import product.repository.SupplierRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use cases for suppliers. Needs MANAGE_PRODUCTS. Names are unique ignoring
 * upper/lower case. Suppliers are never deleted: deactivating one only hides it
 * from the product form, and products that already have it keep it.
 */
public class SupplierService {

    private final SupplierRepository suppliers;
    private final CatalogAccess access;

    public SupplierService(SupplierRepository suppliers, AuthorizationService authz, AuditLogger audit) {
        this.suppliers = Objects.requireNonNull(suppliers, "suppliers");
        this.access = new CatalogAccess(authz, audit);
    }

    /** All suppliers sorted by name; {@code includeInactive} adds the deactivated ones. */
    public List<Supplier> listSuppliers(User actor, boolean includeInactive) throws ProductException {
        access.require(actor);
        return suppliers.findAll(!includeInactive);
    }

    /** Saves a new, active supplier. Only the name is required. */
    public Supplier createSupplier(User actor, String name, String contactPerson, String phone,
                                   String email, String address) throws ProductException {
        access.require(actor);
        Supplier supplier = build(null, name, contactPerson, phone, email, address, true);
        requireNameFree(supplier.name(), null);
        try {
            Supplier saved = suppliers.insert(supplier);
            access.log("SUPPLIER_CREATED", actor, String.valueOf(saved.supplierId()));
            return saved;
        } catch (DuplicateValueException e) {
            throw nameTaken(supplier.name());
        }
    }

    /** Changes a supplier's details. Its active status is kept. */
    public Supplier updateSupplier(User actor, int supplierId, String name, String contactPerson,
                                   String phone, String email, String address) throws ProductException {
        access.require(actor);
        Supplier existing = require(supplierId);
        Supplier changed = build(supplierId, name, contactPerson, phone, email, address, existing.active());
        requireNameFree(changed.name(), supplierId);
        try {
            suppliers.update(changed);
        } catch (DuplicateValueException e) {
            throw nameTaken(changed.name());
        }
        access.log("SUPPLIER_UPDATED", actor, String.valueOf(supplierId));
        return changed;
    }

    public void setActive(User actor, int supplierId, boolean active) throws ProductException {
        access.require(actor);
        Supplier existing = require(supplierId);
        if (existing.active() == active) {
            return;
        }
        suppliers.update(existing.withActive(active));
        access.log(active ? "SUPPLIER_ACTIVATED" : "SUPPLIER_DEACTIVATED", actor, String.valueOf(supplierId));
    }

    private Supplier require(int supplierId) throws ProductException {
        return suppliers.findById(supplierId)
                .orElseThrow(() -> new ProductException("That supplier no longer exists."));
    }

    private void requireNameFree(String name, Integer ownId) throws ProductException {
        Optional<Supplier> same = suppliers.findByName(name);
        if (same.isPresent() && !same.get().supplierId().equals(ownId)) {
            throw nameTaken(name);
        }
    }

    private static ProductException nameTaken(String name) {
        return new ProductException("A supplier named \"" + name + "\" already exists.");
    }

    private static Supplier build(Integer id, String name, String contactPerson, String phone,
                                  String email, String address, boolean active) throws ProductException {
        try {
            return new Supplier(id, name, contactPerson, phone, email, address, active);
        } catch (IllegalArgumentException e) {
            throw new ProductException(e.getMessage());
        }
    }
}
