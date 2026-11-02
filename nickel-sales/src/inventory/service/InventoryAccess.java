package inventory.service;

import auth.contract.User;
import auth.login.AuthenticationService;
import auth.permission.AuthorizationService;
import auth.permission.Permission;

/**
 * Who is asking, and may they use Inventory? Every InventoryService method
 * starts here. A separate interface so the service can be tested without
 * building the whole login stack.
 */
@FunctionalInterface
public interface InventoryAccess {

    /** The logged-in user for this session, if they may manage inventory. */
    User require(String sessionId) throws InventoryException;

    /** The real check: a live session and the UPDATE_INVENTORY permission (as UserAdminService does for users). */
    static InventoryAccess of(AuthenticationService auth, AuthorizationService authz) {
        return sessionId -> {
            User actor = auth.getCurrentUser(sessionId);
            if (actor == null) {
                throw new InventoryException("Your session has expired. Please log in again.");
            }
            if (!authz.checkAccess(actor, Permission.UPDATE_INVENTORY)) {
                throw new InventoryException("You do not have permission to manage inventory.");
            }
            return actor;
        };
    }
}
