package app;

/**
 * The real app's entry point.
 *
 * Wires the shared services ({@link AppContext}) and opens the window.
 * Until the Product &amp; Inventory module is connected (POS Phase 6), POS
 * can't sell here: run test/app/DevApp (run-demo.bat) to try POS with
 * the test products instead.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();

        // Product & Inventory: pass your ProductLookup and StockRecorder here
        // (replace the two nulls), then register any services your screens share:
        //     app.register(YourService.class, yourService);
        AppContext app = AppContext.create(config, null, null);

        System.out.println("[APP] Nickel Sales Center, terminal " + config.terminalId());
        NickelSalesApp.launch(app);
    }
}
