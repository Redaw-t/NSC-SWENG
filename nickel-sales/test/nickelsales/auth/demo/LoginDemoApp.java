package nickelsales.auth.demo;

import javafx.application.Application;
import javafx.stage.Stage;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.session.InMemorySessionManager;
import nickelsales.authz.AuthorizationService;
import nickelsales.ui.dashboard.DashboardRouter;


/**
 * TEST HARNESS ONLY — lives in test/, NOT in src/.
 *
 * Runs the real LoginScreen + InMemorySessionManager + LogoutAction, plus the
 * AUTHZ role's DashboardRouter, against AuthDemo's stub user repository
 * (password "Password123" for all; see AuthDemo for the list).
 *
 * Flow: login screen -> valid login -> role's placeholder dashboard
 * (Admin / Cashier / Inventory) -> Log out -> back to the login screen.
 *
 * Run it with JavaFX on the module path, e.g. VM options:
 *   --module-path /path/to/javafx-sdk/lib --add-modules javafx.controls
 */
public class LoginDemoApp extends Application {

    @Override
    public void start(Stage stage) {
        AuthenticationService auth = new AuthenticationService(
                new AuthDemo.StubUserRepository(),
                new InMemorySessionManager(),
                null,
                (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId));

        DashboardRouter router = new DashboardRouter(stage, auth, new AuthorizationService());

        stage.setTitle("Nickel Sales Center - POS");
        router.showLogin(); //first call creates the scene with the login screen
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}