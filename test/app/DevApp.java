package app;

import pos.stub.StubInventory;

/**
 * DEVELOPMENT launcher: the real app (same login, home screen and modules
 * as {@link Main}), but with the test products from StubInventory instead of
 * the Product &amp; Inventory module, so POS can be tried before that module
 * exists. Users and sales are the real MySQL ones; the test products and
 * their stock live in memory and start fresh every time.
 *
 * This is what double-clicking run-demo.bat starts. Replaced by Main once
 * the real inventory is connected (POS Phase 6).
 */
public final class DevApp {

    private DevApp() { }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        StubInventory testProducts = new StubInventory();
        AppContext app = AppContext.create(config, testProducts, testProducts);
        System.out.println("[DEV] Nickel Sales Center, terminal " + config.terminalId()
                + ", with TEST products (StubInventory) - not the real inventory");
        NickelSalesApp.launch(app);
    }
}
