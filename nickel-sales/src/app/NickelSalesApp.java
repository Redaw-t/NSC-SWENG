package app;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * The JavaFX application: opens the window on the login screen. Started by
 * {@link Main} (the real app) or test/app/DevApp (with test products), which
 * build the {@link AppContext} first and pass it in.
 */
public class NickelSalesApp extends Application {

    private static AppContext context;

    /** Starts the window with these services. Blocks until the window is closed. */
    public static void launch(AppContext app) {
        context = app;
        Application.launch(NickelSalesApp.class);
    }

    @Override
    public void start(Stage stage) {
        if (context == null) {
            throw new IllegalStateException("Start the app with app.Main (or DevApp), not NickelSalesApp directly");
        }
        DashboardRouter router = new DashboardRouter(stage, context, Modules.all());
        stage.setTitle("Nickel Sales Center - POS (" + context.config().terminalId() + ")");
        router.showLogin();
        stage.show();
    }
}
