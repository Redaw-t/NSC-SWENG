package product.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import auth.permission.AuthorizationService;
import auth.permission.Permission;

import java.util.Objects;

/**
 * The permission check and audit log shared by ProductService, CategoryService
 * and SupplierService: changing the catalog needs MANAGE_PRODUCTS (Admin and
 * Inventory staff), and every change is written to the audit log.
 */
final class CatalogAccess {

    private final AuthorizationService authz;
    private final AuditLogger audit;

    CatalogAccess(AuthorizationService authz, AuditLogger audit) {
        this.authz = Objects.requireNonNull(authz, "authz");
        this.audit = audit;   // may be null: then nothing is logged
    }

    /** @throws ProductException the user is missing, inactive, or may not manage products */
    void require(User actor) throws ProductException {
        if (actor == null) {
            throw new ProductException("Your session has expired. Please log in again.");
        }
        if (!authz.checkAccess(actor, Permission.MANAGE_PRODUCTS)) {
            throw new ProductException("You do not have permission to manage products.");
        }
    }

    void log(String event, User actor, String target) {
        if (audit != null) {
            audit.log(event + " target=" + target, actor.getUserId());
        }
    }

    /** As above, with extra words, e.g. before/after pairs "retail=250.00>260.00" (read by the activity log). */
    void log(String event, User actor, String target, String details) {
        if (audit != null) {
            audit.log(event + " target=" + target + " " + details, actor.getUserId());
        }
    }
}
