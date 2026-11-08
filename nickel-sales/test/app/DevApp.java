package app;

import stubs.StubStockRecorder;

/**
 * DEVELOPMENT launcher: the real app (same login, home screen, Product module and
 * wiring as {@link Main}) plus a stand-in for the one part that does not exist yet:
 * the Inventory module's StockRecorder.
 *
 * Users, products, categories, suppliers and sales are all the real MySQL ones
 * (run the database setup first). A sale takes its quantities off the real
 * Inventory.QuantityOnHand, through {@link StubStockRecorder}.
 *
 * This is what double-clicking run-demo.bat starts. Retire it once Main gets
 * the real StockRecorder.
 */
public final class DevApp {

    private DevApp() { }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();

        AppContext app = Main.wire(config);

        System.out.println(
                "[DEV] Nickel Sales Center, terminal " + config.terminalId()
                        + ", using real MySQL products and inventory."
        );

        NickelSalesApp.launch(app);
    }
}
