package nickelsales.auth.demo;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.auth.LoginResult;
import nickelsales.auth.contract.User;
import nickelsales.auth.session.InMemorySessionManager;
import nickelsales.ui.login.LoginScreen;
import nickelsales.ui.login.LogoutAction;

/**
 * TEST HARNESS ONLY — lives in test/, NOT in src/.
 *
 * Runs the real LoginScreen + InMemorySessionManager + LogoutAction against
 * AuthDemo's stub user repository, so the SESSION/UI part can be seen working
 * before the DATA role's real UserRepository and the AUTHZ role's dashboards
 * exist. It is in the same package as AuthDemo so it can reuse the stub
 * accounts (password "Password123" for all; see AuthDemo for the list).
 *
 * Delete this file together with AuthDemo once the real pieces land.
 *
 * Flow: login screen -> valid login -> placeholder dashboard -> Log out ->
 * back to the login screen. Wrong passwords show the error under the fields.
 *
 * Run it with JavaFX on the module path, e.g. VM options:
 *   --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls
 */
public class LoginDemoApp extends Application {

    private Stage stage;
    private LoginScreen loginScreen;
    private LogoutAction logoutAction;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        AuthenticationService auth = new AuthenticationService(
                new AuthDemo.StubUserRepository(),
                new InMemorySessionManager(),
                null,
                (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId));

        loginScreen = new LoginScreen(auth, this::showDashboard);
        logoutAction = new LogoutAction(auth, this::showLogin);

        stage.setTitle("Nickel Sales Center - POS");
        stage.setScene(new Scene(loginScreen.getView(), 1280, 720));
        stage.show();
        loginScreen.focusUserId();
    }

    private void showLogin() {
        loginScreen.reset();
        stage.getScene().setRoot(loginScreen.getView());
        loginScreen.focusUserId();
    }

    /** Placeholder for the AUTHZ role's real role-based dashboards. */
    private void showDashboard(LoginResult result) {
        User user = result.user();
        String sessionId = result.session().getSessionId();

        Label welcome = new Label("Welcome, " + user.getFullName());
        welcome.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        Label roles = new Label("Roles: " + user.getRoles());
        Label screen = new Label("Placeholder screen: " + AuthDemo.dashboards(user));
        Label session = new Label("Session: " + sessionId);

        VBox dashboard = new VBox(16, welcome, roles, screen, session,
                logoutAction.createButton(sessionId));
        dashboard.setAlignment(Pos.CENTER);
        dashboard.setStyle("-fx-background-color: white;");

        stage.getScene().setRoot(dashboard);
    }

    public static void main(String[] args) {
        launch(args);
    }
}