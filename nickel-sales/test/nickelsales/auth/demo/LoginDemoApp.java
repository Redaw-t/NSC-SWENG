package nickelsales.auth.demo;

import nickelsales.data.MySqlUserRepository;
import javafx.application.Application;
import javafx.stage.Stage;
import nickelsales.auth.account.PassPolicy;
import nickelsales.auth.account.UserAccountService;
import nickelsales.auth.account.UserAdminService;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.contract.AuditLogger;
import nickelsales.auth.contract.PasswordPolicy;
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
        MySqlUserRepository repo = new MySqlUserRepository();
        AuditLogger audit = (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId);
        PasswordPolicy policy = new PassPolicy();

        AuthenticationService auth = new AuthenticationService(repo, new InMemorySessionManager(), policy, audit);
        UserAccountService accounts = new UserAccountService(auth, repo, policy, audit);
        AuthorizationService authz = new AuthorizationService();
        UserAdminService admin = new UserAdminService(auth, repo, authz, policy, audit);

        DashboardRouter router = new DashboardRouter(stage, auth, authz, accounts, admin);

        stage.setTitle("Nickel Sales Center - POS");
        router.showLogin();
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}