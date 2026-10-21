package nickelsales.ui.dashboard;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRole;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.auth.LoginResult;
import nickelsales.authz.AuthorizationService;
import nickelsales.authz.Permission;
import nickelsales.ui.login.LoginScreen;
import nickelsales.ui.login.LogoutAction;
import javafx.scene.control.Button;
import nickelsales.auth.account.UserAccountService;
import nickelsales.auth.account.UserAdminService;
import nickelsales.ui.account.ChangePasswordDialog;
import nickelsales.ui.admin.UserManagementScreen;
import nickelsales.ui.UiErrors;

import java.time.Duration;
import java.util.ArrayList;

//
public class DashboardRouter {
    /** Default idle time before automatic logout. Override with NSC_IDLE_TIMEOUT_MINUTES. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final String SESSION_EXPIRED_MESSAGE = "Your session expired. Please log in again.";
    /** While the user is active, keep the server-side session alive at most this often. */
    private static final long SESSION_TOUCH_INTERVAL_MS = 60_000;

    private final Stage stage;
    private final AuthenticationService auth;
    private final AuthorizationService authz;
    private final LoginScreen loginScreen;
    private final LogoutAction logoutAction;
    private final UserAccountService accounts;
    private final UserAdminService admin;
    private final IdleLogoutTimer idleTimer;

    private String currentSessionId;   // null while the login screen is showing
    private long lastSessionTouch;

    public DashboardRouter(Stage stage, AuthenticationService auth, AuthorizationService authz,
                           UserAccountService accounts, UserAdminService admin) {
        this(stage, auth, authz, accounts, admin, idleTimeoutFromEnvironment());
    }

    /** @param idleTimeout how long without mouse/keyboard/touch activity before automatic logout */
    public DashboardRouter(Stage stage, AuthenticationService auth, AuthorizationService authz,
                           UserAccountService accounts, UserAdminService admin, Duration idleTimeout) {
        this.stage = stage;
        this.auth = auth;
        this.authz = authz;
        this.accounts = accounts;
        this.admin = admin;

        this.loginScreen = new LoginScreen(auth, this::openDashboardFor);
        this.logoutAction = new LogoutAction(auth, this::showLogin);
        this.idleTimer = new IdleLogoutTimer(idleTimeout, this::keepSessionAlive, this::expireSession);
    }

    public void showLogin() {
        idleTimer.stop();
        currentSessionId = null;
        loginScreen.reset();
        if (stage.getScene() == null) {
            stage.setScene(new Scene(loginScreen.getView(), 1280, 720)); //for first time, set the scene size as needed
        } else {
            stage.getScene().setRoot(loginScreen.getView());//for subsequent times, just change the root
        }
        loginScreen.focusUserId();
    }

    public void openDashboardFor(LoginResult loginResult) {
        showHome(loginResult.session().getSessionId());
    }

    /**
     * Shows the dashboard that matches the user's CURRENT roles. The user is
     * re-read from the database every time, so a role change made during the
     * session (e.g. an admin removing their own Admin role) takes effect here
     * instead of sending them back to a screen they can no longer open.
     */
    public void showHome(String sessionId) {
        User user;
        try {
            user = auth.getCurrentUser(sessionId);   // reads the user from the database
        } catch (RuntimeException e) {
            // Database unavailable: we can't check who this is or what they may
            // see, so end the session and go to the login screen with a message
            // instead of leaving the user stuck on the current screen.
            System.out.println("[SESSION] cannot load user for " + sessionId + ": " + e.getMessage());
            logoutAction.logout(sessionId);
            loginScreen.showMessage(UiErrors.message(e,
                    "Something went wrong. Please log in again."));
            return;
        }
        if (user == null) {          // session expired or logged out
            showLogin();
            return;
        }
        if (!sessionId.equals(currentSessionId)) {   // first screen after logging in
            currentSessionId = sessionId;
            lastSessionTouch = System.currentTimeMillis();
            idleTimer.start();
        }

        if (user.hasRole(UserRole.ADMIN)) {
            openScreen(sessionId, user, "Admin Dashboard", Permission.MANAGE_SYSTEM);
        } else if (user.hasRole(UserRole.CASHIER)) {
            openScreen(sessionId, user, "Cashier Dashboard", Permission.PROCESS_SALE);
        } else if (user.hasRole(UserRole.INVENTORY_STAFF)) {
            openScreen(sessionId, user, "Inventory Staff Dashboard", Permission.UPDATE_INVENTORY);
        } else {
           System.out.println("[AUTHZ] " + user.getUserId() + " has no role; logging out");
           logoutAction.logout(sessionId);
        }
    }


    private void openScreen(String sessionId, User user, String title, Permission requiredPermission) {
        if (!authz.checkAccess(user, requiredPermission)) {
            // e.g. the account was deactivated mid-session. Never leave the
            // user stuck on the current screen: end the session instead.
            System.out.println("[AUTHZ] " + user.getUserId() + " does not have permission "
                    + requiredPermission + "; logging out");
            logoutAction.logout(sessionId);
            return;
        }
        Button changePassword = new Button("Change password");
        changePassword.setOnAction(e -> new ChangePasswordDialog(stage, accounts, sessionId).show());

        Button logout = logoutAction.createButton(sessionId);

        PHDashboard dashboard;
        if (authz.checkAccess(user, Permission.MANAGE_USERS)) {
            Button manageUsers = new Button("Manage users");
            // "Back" (and losing admin access) goes to showHome, which picks the
            // dashboard from the user's roles at that moment.
            manageUsers.setOnAction(e -> stage.getScene().setRoot(new UserManagementScreen(
                    admin, sessionId, () -> showHome(sessionId)).getView()));
            dashboard = new PHDashboard(title, user, manageUsers, changePassword, logout);
        } else {
            dashboard = new PHDashboard(title, user, changePassword, logout);
        }
        stage.getScene().setRoot(dashboard.getView());
    }

    /**
     * Called by the idle timer when nobody has used the app for the idle timeout.
     * Closes any open dialogs, logs out (so the screen behind is cleared at once),
     * then tells the user why they are back at the login screen.
     */
    private void expireSession() {
        String sessionId = currentSessionId;
        if (sessionId == null) {
            return;
        }
        closeOtherWindows();
        System.out.println("[SESSION] " + sessionId + " expired after inactivity; logging out");
        logoutAction.logout(sessionId);   // ends the session and shows the login screen

        Alert expired = new Alert(Alert.AlertType.INFORMATION, SESSION_EXPIRED_MESSAGE);
        expired.setTitle("Session expired");
        expired.setHeaderText(null);
        expired.initOwner(stage);
        expired.show();
    }

    /**
     * While the user is active, keep the server-side session from timing out
     * (at most once a minute, since it reads the database). If the session has
     * already ended for another reason, treat it as expired.
     */
    private void keepSessionAlive() {
        long now = System.currentTimeMillis();
        if (currentSessionId == null || now - lastSessionTouch < SESSION_TOUCH_INTERVAL_MS) {
            return;
        }
        lastSessionTouch = now;
        try {
            if (auth.getCurrentUser(currentSessionId) == null) {
                Platform.runLater(this::expireSession);
            }
        } catch (RuntimeException e) {
            // e.g. database briefly unavailable: not a reason to log anyone out
        }
    }

    /** Closes dialogs (Edit user, Change password, ...) as if Cancel or X was pressed. */
    private void closeOtherWindows() {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window != stage && window.isShowing()) {
                window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
                if (window.isShowing()) {
                    window.hide();
                }
            }
        }
    }

    /** NSC_IDLE_TIMEOUT_MINUTES (whole minutes, at least 1), else the default. */
    private static Duration idleTimeoutFromEnvironment() {
        String value = System.getenv("NSC_IDLE_TIMEOUT_MINUTES");
        if (value != null) {
            try {
                long minutes = Long.parseLong(value.trim());
                if (minutes >= 1) {
                    return Duration.ofMinutes(minutes);
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
            System.out.println("[SESSION] Ignoring invalid NSC_IDLE_TIMEOUT_MINUTES=" + value);
        }
        return DEFAULT_IDLE_TIMEOUT;
    }
}
