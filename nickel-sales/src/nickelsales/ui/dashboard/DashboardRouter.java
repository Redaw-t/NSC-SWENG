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

    public void openDashboardFor(LoginResult loginResult) {
        User user = loginResult.user();
        String sessionId = loginResult.session().getSessionId();

        if (user.hasRole(UserRole.ADMIN)) {
            openScreen(sessionId, "Admin Dashboard", Permission.MANAGE_SYSTEM);
        } else if (user.hasRole(UserRole.CASHIER)) {
            openScreen(sessionId, "Cashier Dashboard", Permission.PROCESS_SALE);
        } else if (user.hasRole(UserRole.INVENTORY_STAFF)) {
            openScreen(sessionId, "Inventory Staff Dashboard", Permission.UPDATE_INVENTORY);
        } else {
           System.out.println("[AUTHZ] " + user.getUserId() + " has no role; logging out");
           logoutAction.logout(sessionId);
        }
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