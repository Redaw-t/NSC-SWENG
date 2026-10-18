package nickelsales.ui.dashboard;

import javafx.scene.Scene;
import javafx.stage.Stage;
import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRole;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.auth.LoginResult;
import nickelsales.authz.AuthorizationService;
import nickelsales.authz.Permission;
import nickelsales.ui.login.LoginScreen;
import nickelsales.ui.login.LogoutAction;

//
public class DashboardRouter {
    private final Stage stage;
    private final AuthenticationService auth;
    private final AuthorizationService authz;
    private final LoginScreen loginScreen;
    private final LogoutAction logoutAction;

    public DashboardRouter(Stage stage, AuthenticationService auth, AuthorizationService authz) {
        this.stage = stage;
        this.auth = auth;
        this.authz = authz;

        this.loginScreen = new LoginScreen(auth, this::openDashboardFor);
        this.logoutAction = new LogoutAction(auth, this::showLogin);
    }

    public void showLogin() {
        loginScreen.reset();
        if (stage.getScene() == null) {
            stage.setScene(new Scene(loginScreen.getView(), 1280, 720)); //for first time, set the scene size as needed
        } else {
            stage.getScene().setRoot(loginScreen.getView());//for subsequent times, just change the root
        }
        loginScreen.focusUserId();
    }

    /**
     * Sprint 1 landing page. Admin wins when a user holds more than one role.
     * Returns null when the user has no supported role (caller must log them out).
     */
    public static String landingTitleFor(User user) {
        if (user == null) {
            return null;
        }
        if (user.hasRole(UserRole.ADMIN)) {
            return "Admin Dashboard";
        }
        if (user.hasRole(UserRole.CASHIER)) {
            return "Cashier Dashboard";
        }
        if (user.hasRole(UserRole.INVENTORY_STAFF)) {
            return "Inventory Staff Dashboard";
        }
        return null;
    }

    /** Permission the routed dashboard requires. Null when there is no landing page. */
    public static Permission landingPermissionFor(User user) {
        if (user == null) {
            return null;
        }
        if (user.hasRole(UserRole.ADMIN)) {
            return Permission.MANAGE_SYSTEM;
        }
        if (user.hasRole(UserRole.CASHIER)) {
            return Permission.PROCESS_SALE;
        }
        if (user.hasRole(UserRole.INVENTORY_STAFF)) {
            return Permission.UPDATE_INVENTORY;
        }
        return null;
    }

    public void openDashboardFor(LoginResult loginResult) {
        User user = loginResult.user();
        String sessionId = loginResult.session().getSessionId();
        String title = landingTitleFor(user);
        Permission required = landingPermissionFor(user);

        if (title == null || required == null) {
            System.out.println("[AUTHZ] " + user.getUserId() + " has no role; logging out");
            logoutAction.logout(sessionId);
            return;
        }
        openScreen(sessionId, title, required);
    }


    private void openScreen(String sessionId, String title, Permission requiredPermission) {
        User user = auth.getCurrentUser(sessionId);
        if (user == null) {
            showLogin();
            return;
        }

        if (!authz.checkAccess(user, requiredPermission)) {
            System.out.println("[AUTHZ] " + user.getUserId() + " does not have permission " + requiredPermission );
            return;
        }
        PHDashboard dashboard = new PHDashboard(title, user, logoutAction.createButton(sessionId));
        stage.getScene().setRoot(dashboard.getView());
    }
}