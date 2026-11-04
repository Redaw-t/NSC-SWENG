
package app;

import auth.contract.AuditLogger;
import auth.permission.AuthorizationService;
import barcode.service.EscPosLabelPrinter;
import barcode.service.LabelPrinterPort;
import inventory.contract.InsufficientStockException;
import inventory.contract.StockLine;
import inventory.contract.StockOverview;
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
import reports.service.InventoryReportService;
import reports.service.ProductPerformanceService;
import reports.service.ReportsAccess;
import reports.service.SalesReportService;
import reports.service.TransactionHistoryService;
import shared.Database;
import shared.PrinterSettings;
import system.contract.SyncLogWriter;
import system.repository.ActivityLogRepository;
import system.repository.BackupRecordRepository;
import system.repository.MySqlActivityLogRepository;
import system.repository.MySqlBackupRecordRepository;
import system.repository.MySqlInventoryLogRepository;
import system.repository.MySqlSyncLogRepository;
import system.service.ActivityLogService;
import system.service.ActivityLogger;
import system.service.BackupFolderSetting;
import system.service.BackupService;
import system.service.InventoryLogService;
import system.service.SyncLogService;
import system.service.SystemAccess;
import system.service.SystemStatusService;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

public final class Main {

    /** The most unsent sales the status screens count. */
    private static final int UNSYNCED_COUNT_LIMIT = 10000;

    private Main() {}

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        AppContext app = wire(config);

        System.out.println("[APP] Nickel Sales Center, terminal " + config.terminalId());
        NickelSalesApp.launch(app);
    }

    static AppContext wire(AppConfig config) {
        // Every module's audit events are saved to the activity log (and printed, as before).
        ActivityLogRepository activityLog = new MySqlActivityLogRepository();
        AuditLogger audit = new ActivityLogger(activityLog, config.terminalId());

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
        app.register(LabelPrinterPort.class, new EscPosLabelPrinter(config.store().name()));   // Barcode tab: product labels (58 mm)

        // Transaction & Reports: read-only, over POS's SaleHistory.
        ReportsAccess reportsAccess = ReportsAccess.of(app.auth(), app.authz());
        app.register(TransactionHistoryService.class,
                new TransactionHistoryService(app.saleHistory(), reportsAccess));
        app.register(SalesReportService.class, new SalesReportService(app.saleHistory(), reportsAccess));
        app.register(ProductPerformanceService.class,
                new ProductPerformanceService(app.saleHistory(), reportsAccess));
        app.register(CashierReportService.class, new CashierReportService(app.saleHistory(), reportsAccess));
        // Inventory report: Reports gets Inventory's read-only StockOverview contract, never InventoryService.
        StockOverview stockOverview = inventory;
        app.register(StockOverview.class, stockOverview);
        app.register(InventoryReportService.class, new InventoryReportService(stockOverview, reportsAccess));

        // Audit & System module.
        SystemAccess systemAccess = SystemAccess.of(app.auth(), app.authz());
        Clock clock = Clock.systemUTC();
        IntSupplier unsyncedSales = () -> app.saleExport().findUnsynced(UNSYNCED_COUNT_LIMIT).size();
        BackupRecordRepository backupRecords = new MySqlBackupRecordRepository();

        SyncLogService syncLog = new SyncLogService(new MySqlSyncLogRepository(), systemAccess, app.audit(),
                config.terminalId(), unsyncedSales);
        BackupService backups = new BackupService(Database::getConnection, backupRecords, systemAccess, app.audit(),
                config.terminalId(), BackupFolderSetting.load(), BackupFolderSetting::save, clock);

        app.register(ActivityLogService.class, new ActivityLogService(activityLog, systemAccess, app.audit()));
        app.register(InventoryLogService.class,
                new InventoryLogService(new MySqlInventoryLogRepository(), systemAccess, app.audit()));
        app.register(SyncLogService.class, syncLog);
        app.register(SyncLogWriter.class, syncLog);
        app.register(BackupService.class, backups);
        app.register(SystemStatusService.class, new SystemStatusService(systemAccess, List.of(
                SystemStatusService.database(Database::getConnection),
                SystemStatusService.otherTerminal(System.getenv("NSC_PEER_URL")),
                SystemStatusService.printer(PrinterSettings.Use.RECEIPT),
                SystemStatusService.printer(PrinterSettings.Use.LABEL),
                SystemStatusService.pendingSync(unsyncedSales, syncLog::syncConnected),
                SystemStatusService.lastBackup(backupRecords, clock),
                SystemStatusService.diskSpace(backups::folder),
                SystemStatusService.logRetention(activityLog, clock))));

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
