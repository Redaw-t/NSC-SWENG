
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
        shared.Settings.installedApp();                    // read the installer's settings file; it wins over env vars
        java.nio.file.Path log = shared.LogFile.start();   // first: the installed app has no console
        System.out.println("[APP] Nickel Sales Center " + shared.AppVersion.VERSION + " starting; log file "
                + (log == null ? "none" : log.toString()));
        if (!SingleInstance.claim(SingleInstance.defaultFile())) {
            System.out.println("[APP] Already running; this copy closes.");
            javax.swing.JOptionPane.showMessageDialog(null,
                    "Nickel Sales Center is already open. Look for it on the taskbar.",
                    "Nickel Sales Center", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
        }
        start("[APP]");
    }

    /**
     * Builds the app and opens its window; blocks until it is closed. Shared by the installed app
     * ({@link #main}) and development runs ({@code DevApp}), so both pick the terminal ID the same way.
     */
    static void start(String tag) {
        // Every change to the store's records is queued for the other terminals (shared.SyncOutbox).
        shared.SyncOutbox.enable(true);
        AppConfig base = AppConfig.fromSettings();
        // The terminal ID Installation Setup gave this computer wins over NSC_TERMINAL_ID.
        AppConfig config = savedTerminalId().map(base::withTerminalId).orElse(base);
        AppContext app = wire(config);

        System.out.println(tag + " Nickel Sales Center, terminal " + config.terminalId()
                + "; " + shared.Settings.describe());
        // After a first-time setup the app is rebuilt for the terminal ID it was given (e.g. T02).
        NickelSalesApp.launch(app, id -> wire(base.withTerminalId(id)));
    }

    /** This computer's terminal ID from nickelsales_appconfig, if it has been set up and can be read. */
    private static java.util.Optional<String> savedTerminalId() {
        try {
            return new app.install.PosTerminalRepository().thisTerminalId();
        } catch (RuntimeException e) {
            System.err.println("[APP] could not read nickelsales_appconfig (" + (e.getCause() == null ? e : e.getCause())
                    + "); using NSC_TERMINAL_ID. Run setup.bat to create it.");
            return java.util.Optional.empty();
        }
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
        app.register(LabelPrinterPort.class,                                                  // Barcode tab: product labels (58 mm)
                new EscPosLabelPrinter(() -> app.storeDetails().current().name()));

        // Transaction & Reports: read-only, over POS's SaleHistory.
        ReportsAccess reportsAccess = ReportsAccess.of(app.auth(), app.authz());
        app.register(TransactionHistoryService.class,
                new TransactionHistoryService(app.saleHistory(), app.returnHistory(), reportsAccess));   // sales + returns
        app.register(SalesReportService.class, new SalesReportService(app.saleHistory(), app.returnHistory(), reportsAccess));
        app.register(ProductPerformanceService.class,
                new ProductPerformanceService(app.saleHistory(), app.returnHistory(), reportsAccess));
        app.register(CashierReportService.class, new CashierReportService(app.saleHistory(), app.returnHistory(), reportsAccess));
        // Inventory report: Reports gets Inventory's read-only StockOverview contract, never InventoryService.
        StockOverview stockOverview = inventory;
        app.register(StockOverview.class, stockOverview);
        app.register(InventoryReportService.class, new InventoryReportService(stockOverview, reportsAccess));

        // Audit & System module.
        SystemAccess systemAccess = SystemAccess.of(app.auth(), app.authz());
        Clock clock = Clock.systemUTC();
        // Sync with the other terminals: started when the window opens (NickelSalesApp), shown from the start.
        SyncRuntime syncRuntime = new SyncRuntime(sync.SyncDb.standard(), clock);
        app.register(SyncRuntime.class, syncRuntime);
        // Changes (sales, stock, edits ...) waiting to be sent to the Main POS; the sales count as before
        // if this PC has no sync queue yet (a database made by an older script).
        IntSupplier unsyncedSales = () -> {
            try {
                return syncRuntime.db().pendingCount();
            } catch (SQLException e) {
                return app.saleExport().findUnsynced(UNSYNCED_COUNT_LIMIT).size();
            }
        };
        BackupRecordRepository backupRecords = new MySqlBackupRecordRepository();

        SyncLogService syncLog = new SyncLogService(new MySqlSyncLogRepository(), systemAccess, app.audit(),
                config.terminalId(), unsyncedSales);
        BackupService backups = new BackupService(Database::getConnection, backupRecords, systemAccess, app.audit(),
                config.terminalId(), BackupFolderSetting.load(), BackupFolderSetting::save, clock);

        app.register(ActivityLogService.class, new ActivityLogService(activityLog, systemAccess, app.audit()));
        app.register(InventoryLogService.class,
                new InventoryLogService(new MySqlInventoryLogRepository(), systemAccess, app.audit()));
        app.register(system.service.SaleLineLogService.class, new system.service.SaleLineLogService(
                new system.repository.MySqlSaleLineLogRepository(), systemAccess, app.audit()));
        app.register(SyncLogService.class, syncLog);
        app.register(SyncLogWriter.class, syncLog);
        app.register(BackupService.class, backups);
        app.register(system.service.StoreDetailsService.class, app.storeDetails());   // Settings > Store Details
        app.register(SystemStatusService.class, new SystemStatusService(systemAccess, List.of(
                SystemStatusService.database(Database::getConnection),
                () -> syncLink(syncRuntime),
                SystemStatusService.printer(PrinterSettings.Use.RECEIPT),
                SystemStatusService.printer(PrinterSettings.Use.LABEL),
                SystemStatusService.pendingSync(unsyncedSales, () -> syncRuntime.status().map(s -> s.running()).orElse(false)),
                SystemStatusService.lastBackup(backupRecords, clock),
                SystemStatusService.diskSpace(backups::folder),
                SystemStatusService.logRetention(activityLog, clock))));

        return app;
    }

    /** System Status: this PC's link with the other terminals (sync). */
    private static system.model.StatusItem syncLink(SyncRuntime runtime) {
        String name = "Terminal Sync";
        java.util.Optional<sync.SyncStatus> found = runtime.status();
        if (found.isEmpty() || !found.get().running()) {
            return new system.model.StatusItem(name, system.model.StatusItem.Level.INFO, "Not started",
                    "Sync starts once this computer is set up as the Main POS or an Additional POS.");
        }
        sync.SyncStatus s = found.get();
        java.time.format.DateTimeFormatter time = java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a");
        if (s.mainPos()) {
            return s.problem() == null
                    ? new system.model.StatusItem(name, system.model.StatusItem.Level.OK, "Main POS",
                            "Every terminal sends its changes to this computer and gets the others' from it.")
                    : new system.model.StatusItem(name, system.model.StatusItem.Level.WARNING, "Problem", s.problem());
        }
        if (Boolean.TRUE.equals(s.mainReachable())) {
            return new system.model.StatusItem(name, system.model.StatusItem.Level.OK, "Connected",
                    "Syncing with the Main POS at " + s.mainIp() + ". Last sync "
                            + time.format(shared.PhTime.of(s.lastContact())) + ".");
        }
        if (Boolean.FALSE.equals(s.mainReachable())) {
            return new system.model.StatusItem(name, system.model.StatusItem.Level.WARNING, "Main POS offline",
                    "The Main POS at " + s.mainIp() + " can't be reached since "
                            + time.format(shared.PhTime.of(s.offlineSince())) + ". Sales continue here; changes are "
                            + "kept and sent when it is back. Adding or editing products and users needs it.");
        }
        return new system.model.StatusItem(name, system.model.StatusItem.Level.INFO, "Connecting",
                s.problem() == null ? "Reaching the Main POS..." : s.problem());
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

        @Override
        public void recordReturn(String reference, List<StockLine> lines, Connection tx) throws SQLException {
            StockRecorder target = delegate;
            if (target == null) {
                throw new IllegalStateException("InventoryService has not been connected yet.");
            }
            target.recordReturn(reference, lines, tx);
        }
    }
}
