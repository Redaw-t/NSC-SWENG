package app;

import auth.contract.User;
import javafx.stage.Stage;

/**
 * What a module's screen gets when it opens.
 *
 * @param stage     the app window (owner for dialogs)
 * @param sessionId the logged-in session; pass it to services that check who is asking
 * @param user      the logged-in user, freshly read when the screen was opened
 * @param app       the shared services
 * @param home      shows the home screen again (re-checks the user's access); use {@link #goHome()}
 */
public record ScreenContext(Stage stage, String sessionId, User user, AppContext app, Runnable home) {

    /** For a Back button: {@code back.setOnAction(e -> screen.goHome())}. */
    public void goHome() {
        home.run();
    }
}
