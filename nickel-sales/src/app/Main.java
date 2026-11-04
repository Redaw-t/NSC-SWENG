
package app;

import auth.contract.AuditLogger;
import auth.permission.AuthorizationService;
import inventory.contract.InsufficientStockException;
import inventory.contract.StockLine;
import inventory.contract.StockRecorder;
import inventory.repository.MySqlInventoryRepository;
import inventory.service.InventoryAccess;
import inventory.service.InventoryService;
import product.repository.CategoryRepository;
import product.repository.MySqlCategoryRepository;
import product.repository.MySqlProductRepository;
import product.repository.MySqlSupplierRepository;
import product.repository.ProductRepository;
import product.repository.SupplierRepository;
import product.service.CategoryService;
import product.service.ProductService;
import product.service.SupplierService;
import reports.service.CashierReportService;
import reports.service.ProductPerformanceService;
import reports.service.ReportsAccess;
import reports.service.SalesReportService;
import reports.service.TransactionHistoryService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        AppContext app = wire(config);

        System.out.println("[APP] Nickel Sales Center, terminal " + config.terminalId());
        NickelSalesApp.launch(app);
    }

    static AppContext wire(AppConfig config) {
        AuditLogger audit = (event, userId) ->
                System.out.println("[AUDIT] " + event + " " + userId);

        AuthorizationService authz = new AuthorizationService();

        // Product module: create ONE ProductService for the whole application.
        ProductRepository productRepository = new MySqlProductRepository();
        CategoryRepository categoryRepository = new MySqlCategoryRepository();
        SupplierRepository supplierRepository = new MySqlSupplierRepository();

        ProductService products = new ProductService(
                productRepository,
                categoryRepository,
                supplierRepository,
                authz,
                audit
        );

        // Temporary connector: AppContext needs a StockRecorder during creation.
        DeferredStockRecorder stock = new DeferredStockRecorder();

        AppContext app = AppContext.create(config, products, stock, audit);

        // Now that AppContext exists, create the real InventoryService.
        InventoryService inventory = new InventoryService(
                new MySqlInventoryRepository(),
                products,
                InventoryAccess.of(app.auth(), app.authz()),
                app.audit()
        );

        // Connect POS to the real InventoryService.
        stock.setDelegate(inventory);

        // Register the module services for the screens.
        app.register(ProductService.class, products);
        app.register(CategoryService.class,
                new CategoryService(categoryRepository, authz, audit));
        app.register(SupplierService.class,
                new SupplierService(supplierRepository, authz, audit));
        app.register(InventoryService.class, inventory);

        // Transaction & Reports: read-only, over POS's SaleHistory.
        ReportsAccess reportsAccess = ReportsAccess.of(app.auth(), app.authz());
        app.register(TransactionHistoryService.class,
                new TransactionHistoryService(app.saleHistory(), reportsAccess));
        app.register(SalesReportService.class, new SalesReportService(app.saleHistory(), reportsAccess));
        app.register(ProductPerformanceService.class,
                new ProductPerformanceService(app.saleHistory(), reportsAccess));
        app.register(CashierReportService.class, new CashierReportService(app.saleHistory(), reportsAccess));

        return app;
    }

    /**
     * Passes POS stock requests to the real InventoryService.
     */
    private static final class DeferredStockRecorder implements StockRecorder {
        private volatile StockRecorder delegate;

        void setDelegate(StockRecorder delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public void recordSale(
                String reference,
                List<StockLine> lines,
                Connection tx
        ) throws InsufficientStockException, SQLException {

            StockRecorder target = delegate;

            if (target == null) {
                throw new IllegalStateException(
                        "InventoryService has not been connected yet."
                );
            }

            target.recordSale(reference, lines, tx);
        }
    }
}
