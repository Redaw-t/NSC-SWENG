package auth.ui;

import javafx.scene.control.Button;
import auth.login.AuthenticationService;

import java.util.Objects;

/**
 * LogoutAction — Sprint 1, owned by the SESSION/UI role.
 *
 * Ends the session through {@link AuthenticationService#logout(String)} and
 * then shows the login screen again. The login screen is shown even if ending
 * the session throws, so a user is never left stuck on a dashboard.
 *
 * Every dashboard gets one of these buttons:
 * <pre>
 *   LogoutAction logout = new LogoutAction(auth, () -> router.showLogin());
 *   toolbar.getChildren().add(logout.createButton(result.session().getSessionId()));
 * </pre>
 */
public class LogoutAction {

    private final AuthenticationService auth;
    private final Runnable showLoginScreen;

    /**
     * @param showLoginScreen what to run after logout: reset the login screen
     *                        and put it back in the window
     */
    public LogoutAction(AuthenticationService auth, Runnable showLoginScreen) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.showLoginScreen = Objects.requireNonNull(showLoginScreen, "showLoginScreen");
    }

    /** Ends the session, then returns to the login screen. Safe on unknown or expired ids. */
    public void logout(String sessionId) {
        try {
            auth.logout(sessionId);
        } finally {
            showLoginScreen.run();
        }
    }

    /** A ready-made "Log out" button for a dashboard's toolbar. */
    public Button createButton(String sessionId) {
        Button button = new Button("Log out");
        button.setOnAction(e -> logout(sessionId));
        return button;
    }
}