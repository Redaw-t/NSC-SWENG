package app;

import app.install.InstallationSetupScreen;
import app.install.PosTerminalRepository;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import sync.api.ApiServer;

import java.io.IOException;
import java.util.function.Function;

/**
 * The JavaFX application: opens the window on the login screen, or on INSTALLATION SETUP while this
 * computer is not set up yet. Started by {@link Main}, which builds the {@link AppContext} first and
 * passes it in. On the Main POS (terminal T01) it also runs the API the other terminals call.
 */
public class NickelSalesApp extends Application {

    private static AppContext context;
    /** Builds the app again for another terminal ID (after setup gave this computer one). */
    private static Function<String, AppContext> rebuild;

    private final PosTerminalRepository terminals = new PosTerminalRepository();
    private ApiServer api;

    /** Starts the window with these services (dev/test runs: setup keeps the same services). */
    public static void launch(AppContext app) {
        launch(app, null);
    }

    /** Starts the window with these services. Blocks until the window is closed. */
    public static void launch(AppContext app, Function<String, AppContext> rebuildFor) {
        context = app;
        rebuild = rebuildFor;
        Application.launch(NickelSalesApp.class);
    }

    @Override
    public void start(Stage stage) {
        if (context == null) {
            throw new IllegalStateException("Start the app through app.Main, not NickelSalesApp directly");
        }
        shared.GlobalStyles.installEverywhere();   // focus ring on Tab, Dashboard-style scroll bars everywhere
        java.io.InputStream icon = NickelSalesApp.class.getResourceAsStream("/images/nickel-logo-icon.png");
        if (icon != null) {
            stage.getIcons().add(new javafx.scene.image.Image(icon));
        }
        stage.setMaximized(true);
        if (needsSetup()) {
            stage.setTitle("Nickel Sales Center - Installation Setup");
            InstallationSetupScreen setup = new InstallationSetupScreen(terminals, context.firstAdmin(), context.storeDetails(), id -> {
                if (!id.equals(context.config().terminalId()) && rebuild != null) {
                    context = rebuild.apply(id);   // e.g. T02: invoice numbers, logs and backups use the new ID
                }
                openApp(stage);
            });
            stage.setScene(new Scene(setup.getView(), 1280, 720));
        } else {
            openApp(stage);
        }
        stage.show();
    }

    /** The normal app: login page first; the API too when this computer is the Main POS. */
    private void openApp(Stage stage) {
        DashboardRouter router = new DashboardRouter(stage, context, Modules.all());
        // The version is shown at the bottom right of Settings ("App Version: ..."), not in the title.
        stage.setTitle("Nickel Sales Center - POS (" + context.config().terminalId() + ")");
        router.showLogin();
        context.find(system.service.AutoBackup.class).ifPresent(system.service.AutoBackup::start);   // hourly backup
        startSync();
        startApiIfMain();
    }

    /** Sync with the other terminals (see SyncRuntime), once this computer is set up. */
    private void startSync() {
        context.find(SyncRuntime.class).ifPresent(sync -> {
            try {
                sync.start(context, terminals);
            } catch (RuntimeException e) {
                System.err.println("[SYNC] not started: " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
            }
        });
    }

    /**
     * True while this computer is not set up (see PosTerminalRepository.needsSetup). If
     * nickelsales_appconfig can't be read (e.g. setup.bat has not created it yet), the login page is
     * shown as before, so a missing database never locks anyone out.
     */
    private boolean needsSetup() {
        try {
            return terminals.needsSetup();
        } catch (RuntimeException e) {
            System.err.println("[SETUP] could not check nickelsales_appconfig ("
                    + (e.getCause() == null ? e : e.getCause()) + "). Showing the login page; run setup.bat.");
            return false;
        }
    }

    /** Starts the API the Additional POS terminals call, if this computer is the Main POS (T01). */
    private void startApiIfMain() {
        if (api != null) {
            return;
        }
        try {
            boolean main = terminals.thisTerminalId().map(PosTerminalRepository.MAIN_ID::equals).orElse(false);
            if (main) {
                // The new Additional POS's first sync: the whole store database, never nickelsales_appconfig;
                // afterwards every terminal's changes go through this POS (the sync calls, see sync.SyncEngine).
                sync.api.SyncHub hub = context.find(SyncRuntime.class).flatMap(SyncRuntime::engine).orElse(null);
                api = ApiServer.start(ApiServer.port(), PosTerminalRepository.MAIN_ID, terminals,
                        out -> app.install.InitialSync.writeStoreData(out, PosTerminalRepository.MAIN_ID), hub);
            }
        } catch (IOException e) {
            System.err.println("[API] could not listen on port " + ApiServer.port() + " (" + e.getMessage()
                    + "). Is the app already open? Other terminals can't connect until it is fixed.");
        } catch (RuntimeException e) {
            System.err.println("[API] not started: " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
        }
    }

    @Override
    public void stop() {
        if (api != null) {
            api.stop();
        }
        context.find(SyncRuntime.class).ifPresent(SyncRuntime::stop);
        context.find(system.service.AutoBackup.class).ifPresent(system.service.AutoBackup::stop);
    }
}
