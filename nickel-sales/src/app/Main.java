package app;

import auth.contract.AuditLogger;
import auth.permission.AuthorizationService;
import inventory.contract.StockRecorder;
import product.repository.CategoryRepository;
import product.repository.MySqlCategoryRepository;
import product.repository.MySqlProductRepository;
import product.repository.MySqlSupplierRepository;
import product.repository.ProductRepository;
import product.repository.SupplierRepository;
import product.service.CategoryService;
import product.service.ProductService;
import product.service.SupplierService;

/**
 * The real app's entry point (the composition root).
 *
 * Wires the shared services ({@link AppContext}) and the Product module, then
 * opens the window. This is the only place that says {@code new MySql...Repository()}.
 *
 * POS now finds real products in MySQL through ProductService. It still can't
 * SELL here until the Inventory module supplies a StockRecorder (replace the
 * {@code null} in {@link #main}); run test/app/DevApp (run-demo.bat) to try
 * selling with a stand-in recorder.
 *
 * This class must not import anything from test/.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();

        // Inventory module: pass your inventory.contract.StockRecorder here (replace the null)
        // and register any services your screens share, inside wire(...):
        //     app.register(YourService.class, yourService);
        AppContext app = wire(config, null);

        System.out.println("[APP] Nickel Sales Center, terminal " + config.terminalId());
        NickelSalesApp.launch(app);
    }

    /**
     * Builds the app's services: the shared ones plus the Product module backed by MySQL.
     * DevApp calls this too, adding a stand-in StockRecorder.
     *
     * @param stock the Inventory module's StockRecorder, or null while it does not exist
     */
    static AppContext wire(AppConfig config, StockRecorder stock) {
        AuditLogger audit = (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId);
        AuthorizationService authz = new AuthorizationService();

        // Product module: repositories -> services. ProductService is also POS's ProductLookup.
        ProductRepository productRepository = new MySqlProductRepository();
        CategoryRepository categoryRepository = new MySqlCategoryRepository();
        SupplierRepository supplierRepository = new MySqlSupplierRepository();
        ProductService products = new ProductService(productRepository, categoryRepository,
                supplierRepository, authz, audit);

        AppContext app = AppContext.create(config, products, stock, audit);
        app.register(ProductService.class, products);
        app.register(CategoryService.class, new CategoryService(categoryRepository, authz, audit));
        app.register(SupplierService.class, new SupplierService(supplierRepository, authz, audit));
        return app;
    }
}
