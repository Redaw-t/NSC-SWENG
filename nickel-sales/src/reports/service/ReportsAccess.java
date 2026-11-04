package reports.service;

import auth.contract.User;
import auth.login.AuthenticationService;
import auth.permission.AuthorizationService;
import auth.permission.Permission;

import java.util.Objects;
import java.util.function.Function;

/**
 * Who is asking, and may they see this report? Every report service method starts here.
 * A separate interface so the services can be tested without the whole login stack
 * (same idea as InventoryAccess).
 *
 * Sales reports need VIEW_SALES_REPORTS (Admin); inventory reports need
 * VIEW_INVENTORY_REPORTS (Admin, Inventory staff). Cashiers get neither.
 */
@FunctionalInterface
public interface ReportsAccess {

    /** The logged-in user for this session, if they hold {@code needed}. */
    User require(String sessionId, Permission needed) throws ReportException;

    /** The real check: a live session and the permission. */
    static ReportsAccess of(AuthenticationService auth, AuthorizationService authz) {
        Objects.requireNonNull(auth, "auth");
        return of(auth::getCurrentUser, authz);
    }

    /**
     * The same check with any way of finding the session's user (null = no live session).
     * The self-test uses this with a map of sessions.
     */
    static ReportsAccess of(Function<String, User> currentUser, AuthorizationService authz) {
        Objects.requireNonNull(currentUser, "currentUser");
        Objects.requireNonNull(authz, "authz");
        return (sessionId, needed) -> {
            User actor = sessionId == null ? null : currentUser.apply(sessionId);
            if (actor == null) {
                throw new ReportException("Your session has expired. Please log in again.");
            }
            if (!authz.checkAccess(actor, needed)) {
                throw new ReportException(needed == Permission.VIEW_INVENTORY_REPORTS
                        ? "You do not have permission to view inventory reports."
                        : "You do not have permission to view sales reports.");
            }
            return actor;
        };
    }
}
