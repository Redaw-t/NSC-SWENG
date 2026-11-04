package system.service;

import auth.contract.User;
import auth.login.AuthenticationService;
import auth.permission.AuthorizationService;
import auth.permission.Permission;

/**
 * Who is asking, and may they use the System module? Every System service method starts
 * here. A separate interface so the services can be tested without the whole login stack.
 */
@FunctionalInterface
public interface SystemAccess {

    /** The logged-in user for this session, if they may manage the system. */
    User require(String sessionId) throws SystemException;

    /** The real check: a live session and the MANAGE_SYSTEM permission (Admin). */
    static SystemAccess of(AuthenticationService auth, AuthorizationService authz) {
        return sessionId -> {
            User actor = auth.getCurrentUser(sessionId);
            if (actor == null) {
                throw new SystemException("Your session has expired. Please log in again.");
            }
            if (!authz.checkAccess(actor, Permission.MANAGE_SYSTEM)) {
                throw new SystemException("You do not have permission to view system logs.");
            }
            return actor;
        };
    }
}
