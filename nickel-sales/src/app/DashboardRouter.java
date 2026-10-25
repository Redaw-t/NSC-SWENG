package app;

import auth.contract.User;
import auth.login.LoginResult;
import auth.ui.ChangePasswordDialog;
import auth.ui.LoginScreen;
import auth.ui.LogoutAction;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import shared.UiErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Moves the window between the login screen, the home screen and the
 * modules' screens, for the logged-in session.
 *
 * - After login, and every time the user comes back home, the user is
 *   re-read from the database, so role changes take effect at once.
 * - Pressing a module's button re-checks its permission before opening.
 * - If the database can't be reached, the session ends and the login screen
 *   says so (shared.UiErrors), instead of leaving the user stuck.
 * - Idle logout: no keyboard / mouse / touch for the idle timeout
 *   (AppConfig) closes dialogs, logs out and says why.
 */
public class DashboardRouter {

    public static final String SESSION_EXPIRED_MESSAGE = "Your session expired. Please log in again.";
    public static final String NO_SCREENS_MESSAGE =
            "Your account has no screens to open. Ask an admin to check your role.";
    /** While the user is active, keep the server-side session alive at most this often. */
    private static final long SESSION_TOUCH_INTERVAL_MS = 60_000;

    private final Stage stage;
    private final AppContext app;
    private final List<AppModule> modules;
    private final LoginScreen loginScreen;
    private final LogoutAction logoutAction;
    private final IdleLogoutTimer idleTimer;

    private String currentSessionId;   // null while the login screen is showing
    private long lastSessionTouch;

    /** @param modules the home screen's buttons, normally {@link Modules#all()} */
    public DashboardRouter(Stage stage, AppContext app, List<AppModule> modules) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.app = Objects.requireNonNull(app, "app");
        this.modules = List.copyOf(modules);
        this.loginScreen = new LoginScreen(app.auth(), this::openHomeFor);
        this.logoutAction = new LogoutAction(app.auth(), this::showLogin);
        this.idleTimer = new IdleLogoutTimer(app.config().idleTimeout(), this::keepSessionAlive, this::expireSession);
    }

    public void showLogin() {
        idleTimer.stop();
        currentSessionId = null;
        loginScreen.reset();
        show(loginScreen.getView());
        loginScreen.focusUserId();
    }

    private void openHomeFor(LoginResult loginResult) {
        showHome(loginResult.session().getSessionId());
    }

    /** Shows the home screen with the buttons the user may use right now. */
    public void showHome(String sessionId) {
        User user = currentUser(sessionId);
        if (user == null) {
            return;   // already sent to the login screen
        }
        List<AppModule> visible = Modules.visibleFor(user, app.authz(), modules);
        if (visible.isEmpty()) {
            // Inactive, locked, or no role that opens anything: end the session.
            System.out.println("[AUTHZ] " + user.getUserId() + " has no screens it may open; logging out");
            logoutAction.logout(sessionId);
            loginScreen.showMessage(NO_SCREENS_MESSAGE);
            return;
        }
        if (!sessionId.equals(currentSessionId)) {   // first screen after logging in
            currentSessionId = sessionId;
            lastSessionTouch = System.currentTimeMillis();
            idleTimer.start();
        }

        Button changePassword = new Button("Change password");
        changePassword.setOnAction(e -> new ChangePasswordDialog(stage, app.accounts(), sessionId).show());
        Button logout = logoutAction.createButton(sessionId);

        HomeScreen home = new HomeScreen(user, app.config().terminalId(), visible,
                module -> open(sessionId, module), changePassword, logout);
        show(home.getView());
        home.focusFirst();
    }

    /** Opens a module's screen after checking, again, that the user may use it. */
    private void open(String sessionId, AppModule module) {
        User user = currentUser(sessionId);
        if (user == null) {
            return;
        }
        if (!app.authz().checkAccess(user, module.permission())) {
            System.out.println("[AUTHZ] " + user.getUserId() + " may no longer open " + module.title());
            showHome(sessionId);
            return;
        }
        try {
            show(module.open(new ScreenContext(stage, sessionId, user, app, () -> showHome(sessionId))));
        } catch (RuntimeException e) {
            System.out.println("[APP] could not open " + module.title() + ": " + e);
            Alert error = new Alert(Alert.AlertType.ERROR,
                    UiErrors.message(e, module.title() + " could not be opened. Try again, "
                            + "or contact the system administrator."));
            error.setTitle(module.title());
            error.setHeaderText(null);
            error.initOwner(stage);
            error.show();
        }
    }

    /**
     * The session's user, re-read from the database; null (after going to the
     * login screen, with a message if the database is down) if there is none.
     */
    private User currentUser(String sessionId) {
        try {
            User user = app.auth().getCurrentUser(sessionId);
            if (user == null) {          // session expired or logged out
                showLogin();
            }
            return user;
        } catch (RuntimeException e) {
            System.out.println("[SESSION] cannot load user for " + sessionId + ": " + e.getMessage());
            logoutAction.logout(sessionId);
            loginScreen.showMessage(UiErrors.message(e, "Something went wrong. Please log in again."));
            return null;
        }
    }

    private void show(javafx.scene.Parent root) {
        if (stage.getScene() == null) {
            stage.setScene(new Scene(root, 1280, 720));
        } else {
            stage.getScene().setRoot(root);
        }
    }

    /**
     * Called by the idle timer when nobody has used the app for the idle timeout.
     * Closes any open dialogs, logs out (so the screen behind is cleared at once),
     * then tells the user why they are back at the login screen: in red under
     * the login fields (always visible) and in a small popup.
     */
    private void expireSession() {
        String sessionId = currentSessionId;
        if (sessionId == null) {
            return;
        }
        closeOtherWindows();
        System.out.println("[SESSION] " + sessionId + " expired after inactivity; logging out");
        logoutAction.logout(sessionId);   // ends the session and shows the login screen
        loginScreen.showMessage(SESSION_EXPIRED_MESSAGE);

        // Open the popup on the next UI pass, once the login screen has been
        // drawn: a dialog opened in the same pass that swaps the screen (from
        // inside the idle timer's animation) can come up blank on Windows.
        Platform.runLater(() -> {
            if (currentSessionId != null) {
                return;   // someone already logged in again
            }
            Alert expired = new Alert(Alert.AlertType.INFORMATION, SESSION_EXPIRED_MESSAGE);
            expired.setTitle("Session expired");
            expired.setHeaderText(null);
            expired.initOwner(stage);
            // Let the box grow to fit its text (otherwise the text can be cut off or missing).
            expired.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            expired.getDialogPane().setMinWidth(Region.USE_PREF_SIZE);
            expired.setResizable(true);
            expired.show();
        });
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
            if (app.auth().getCurrentUser(currentSessionId) == null) {
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
}
