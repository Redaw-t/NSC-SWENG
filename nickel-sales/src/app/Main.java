package app;

/**
 * The real app's entry point (the composition root).
 *
 * Wires the shared services ({@link AppContext}) and opens the window.
 * Until the Product and Inventory modules exist, POS can't sell here
 * (it needs a ProductLookup and a StockRecorder): run test/app/DevApp
 * (run-demo.bat) to try POS with the test products instead.
 *
 * This class must not import anything from test/.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();

        // Product / Inventory modules: pass your product.contract.ProductLookup and
        // inventory.contract.StockRecorder here (replace the two nulls), then
        // register any services your screens share:
        //     app.register(YourService.class, yourService);
        AppContext app = AppContext.create(config, null, null);

        System.out.println("[APP] Nickel Sales Center, terminal " + config.terminalId());
        NickelSalesApp.launch(app);
    }
}
