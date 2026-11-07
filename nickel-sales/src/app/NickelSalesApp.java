package app;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * The JavaFX application: opens the window on the login screen. Started by
 * {@link Main}, which builds the {@link AppContext} first and passes it in.
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
            throw new IllegalStateException("Start the app through app.Main, not NickelSalesApp directly");
        }
        shared.GlobalStyles.installEverywhere();   // focus ring on Tab, Dashboard-style scroll bars everywhere
        DashboardRouter router = new DashboardRouter(stage, context, Modules.all());
        java.io.InputStream icon = NickelSalesApp.class.getResourceAsStream("/images/nickel-logo-icon.png");
        if (icon != null) {
            stage.getIcons().add(new javafx.scene.image.Image(icon));
        }
        stage.setTitle("Nickel Sales Center - POS (" + context.config().terminalId() + ")");
        stage.setMaximized(true);  
        router.showLogin();
        stage.show();
    }
}
