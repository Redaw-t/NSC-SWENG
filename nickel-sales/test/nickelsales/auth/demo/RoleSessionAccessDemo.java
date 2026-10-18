package nickelsales.auth.demo;

import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.auth.LoginResult;
import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRepository;
import nickelsales.auth.contract.UserRole;
import nickelsales.auth.contract.UserStatus;
import nickelsales.auth.session.InMemorySessionManager;
import nickelsales.authz.AuthorizationService;
import nickelsales.authz.Permission;
import nickelsales.ui.dashboard.DashboardRouter;

import java.time.Duration;
import java.util.Set;

/**
 * QA harness for Rhian's Sprint 1 scope: role redirect, permission checks,
 * logout, unauthenticated access, and session timeout.
 *
 * These are service-level checks (no JavaFX window). Use them together with
 * the manual GUI cases in qa/Sprint1-Rhian-Role-Session-Access.md.
 *
 * Run after compile (from nickel-sales/):
 *   run-demo.bat --qa
 *   java -cp "out;lib\jbcrypt-0.4.jar" nickelsales.auth.demo.RoleSessionAccessDemo
 */
public class RoleSessionAccessDemo {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        UserRepository users = new AuthDemo.StubUserRepository();
        AuthorizationService authz = new AuthorizationService();
        InMemorySessionManager sessions = new InMemorySessionManager(Duration.ofMillis(250));
        AuthenticationService auth = new AuthenticationService(users, sessions, null, null);

        User admin = users.findById("NSC-00001");
        User cashier = users.findById("NSC-00002");
        User inventory = users.findById("NSC-00003");
        User inactive = users.findById("NSC-00004");
        User locked = users.findById("NSC-00005");
        User noRole = new User("NSC-00099", "unused", "No", "Role", Set.of(), UserStatus.ACTIVE);

        System.out.println("=================================================================");
        System.out.println(" QA - Role / session / access-control (Rhian)");
        System.out.println("=================================================================");

        // TC-01 Role-based login redirect (Admin wins for multi-role users)
        check("TC-01 NSC-00001 (A;C;I) lands on Admin Dashboard",
                "Admin Dashboard".equals(DashboardRouter.landingTitleFor(admin)));
        check("TC-01 NSC-00002 (C) lands on Cashier Dashboard",
                "Cashier Dashboard".equals(DashboardRouter.landingTitleFor(cashier)));
        check("TC-01 NSC-00003 (I) lands on Inventory Staff Dashboard",
                "Inventory Staff Dashboard".equals(DashboardRouter.landingTitleFor(inventory)));
        check("TC-01 user with no role has no landing page",
                DashboardRouter.landingTitleFor(noRole) == null);

        // TC-02 Access to authorized functions (permission matrix + landing permission)
        check("TC-02 Admin is allowed MANAGE_SYSTEM (Admin Dashboard)",
                authz.checkAccess(admin, DashboardRouter.landingPermissionFor(admin)));
        check("TC-02 Admin is also allowed PROCESS_SALE (multi-role)",
                authz.checkAccess(admin, Permission.PROCESS_SALE));
        check("TC-02 Cashier is allowed PROCESS_SALE (Cashier Dashboard)",
                authz.checkAccess(cashier, DashboardRouter.landingPermissionFor(cashier)));
        check("TC-02 Inventory is allowed UPDATE_INVENTORY (Inventory Dashboard)",
                authz.checkAccess(inventory, DashboardRouter.landingPermissionFor(inventory)));
        check("TC-02 Inventory is allowed MANAGE_PRODUCTS and VIEW_REPORTS",
                authz.checkAccess(inventory, Permission.MANAGE_PRODUCTS)
                        && authz.checkAccess(inventory, Permission.VIEW_REPORTS));

        // TC-03 Block access to unauthorized functions
        check("TC-03 Cashier is denied MANAGE_SYSTEM",
                !authz.checkAccess(cashier, Permission.MANAGE_SYSTEM));
        check("TC-03 Cashier is denied UPDATE_INVENTORY",
                !authz.checkAccess(cashier, Permission.UPDATE_INVENTORY));
        check("TC-03 Inventory is denied PROCESS_SALE",
                !authz.checkAccess(inventory, Permission.PROCESS_SALE));
        check("TC-03 Inventory is denied MANAGE_USERS",
                !authz.checkAccess(inventory, Permission.MANAGE_USERS));
        check("TC-03 null user is denied",
                !authz.checkAccess(null, Permission.PROCESS_SALE));
        check("TC-03 inactive user is denied even PROCESS_SALE",
                !authz.checkAccess(inactive, Permission.PROCESS_SALE));
        check("TC-03 locked user is denied even PROCESS_SALE",
                !authz.checkAccess(locked, Permission.PROCESS_SALE));

        // TC-12 Unauthenticated protected access
        check("TC-12 getCurrentUser(null) is null",
                auth.getCurrentUser(null) == null);
        check("TC-12 getCurrentUser(unknown id) is null",
                auth.getCurrentUser("not-a-real-session") == null);
        check("TC-12 isSessionValid(unknown id) is false",
                !sessions.isSessionValid("not-a-real-session"));

        // TC-13 Logout ends the session
        LoginResult loggedIn = auth.login("NSC-00002", AuthDemo.TEST_PASSWORD);
        String sessionId = loggedIn.session().getSessionId();
        check("TC-13 session is valid after login",
                auth.getCurrentUser(sessionId) != null);
        auth.logout(sessionId);
        check("TC-13 getCurrentUser is null after logout",
                auth.getCurrentUser(sessionId) == null);
        check("TC-13 isSessionValid is false after logout",
                !sessions.isSessionValid(sessionId));
        auth.logout(sessionId);
        check("TC-13 logout of an already-ended session does not throw", true);

        // TC-15 Session timeout (short duration so QA does not wait 30 minutes)
        LoginResult timed = auth.login("NSC-00003", AuthDemo.TEST_PASSWORD);
        String timedId = timed.session().getSessionId();
        check("TC-15 session is valid immediately after login",
                sessions.isSessionValid(timedId));
        Thread.sleep(400);
        check("TC-15 idle session expires and cannot be used",
                auth.getCurrentUser(timedId) == null && !sessions.isSessionValid(timedId));

        LoginResult keptAlive = auth.login("NSC-00003", AuthDemo.TEST_PASSWORD);
        String aliveId = keptAlive.session().getSessionId();
        Thread.sleep(150);
        check("TC-15 getCurrentUser before expiry keeps the session alive",
                auth.getCurrentUser(aliveId) != null);
        Thread.sleep(150);
        check("TC-15 recently used session is still valid",
                sessions.isSessionValid(aliveId));

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULTS: " + passed + " tests passed, " + failed + " tests failed.");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String description, boolean condition) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + description);
        if (condition) {
            passed++;
        } else {
            failed++;
        }
    }
}
