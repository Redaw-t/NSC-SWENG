package app;

import auth.account.PassPolicy;
import auth.account.UserAccountService;
import auth.account.UserAdminService;
import auth.contract.AuditLogger;
import auth.contract.PasswordPolicy;
import auth.contract.UserRepository;
import auth.login.AuthenticationService;
import auth.permission.AuthorizationService;
import auth.repository.MySqlUserRepository;
import auth.session.InMemorySessionManager;
import inventory.contract.StockRecorder;
import pos.cart.PricingService;
import pos.cart.SalesService;
import pos.checkout.CheckoutService;
import pos.checkout.PaymentService;
import pos.contract.SaleExport;
import pos.contract.SaleHistory;
import pos.receipt.JavaFxReceiptPrinter;
import pos.receipt.PrinterPort;
import pos.receipt.ReceiptBuilder;
import pos.repository.InvoiceNumberGenerator;
import pos.repository.MySqlSaleRepository;
import product.contract.ProductLookup;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The app's shared services, made ONCE at start-up and handed to every
 * screen (through {@link ScreenContext#app()}). This is the single wiring
 * spot: no screen creates its own AuthenticationService, repository, etc.
 *
 * What other modules get from here:
 * - everyone: {@link #auth()}, {@link #authz()}, {@link #audit()}, {@link #config()}
 * - Sync: {@link #saleExport()} (completed sales not yet synced)
 * - Reports: {@link #saleHistory()} (completed sales by ID, invoice no., date range)
 * - POS: {@link #salesService()}, {@link #payments()}, {@link #receipts()}, {@link #receiptPrinter()}
 *
 * A module's OWN services (e.g. an inventory service its screens share) are
 * added with {@link #register} in Main and read with {@link #find}, so
 * adding one never means changing this class.
 */
public final class AppContext {

    private final AppConfig config;
    private final AuditLogger audit;
    private final UserRepository users;
    private final AuthenticationService auth;
    private final AuthorizationService authz;
    private final UserAccountService accounts;
    private final UserAdminService admin;
    private final MySqlSaleRepository sales;
    private final PaymentService payments;
    private final ProductLookup products;          // null until Product & Inventory is connected
    private final SalesService salesService;       // null without products and stock
    private final ReceiptBuilder receipts;
    private final PrinterPort printer;
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    private AppContext(AppConfig config, AuditLogger audit, ProductLookup products, StockRecorder stock) {
        this.config = Objects.requireNonNull(config, "config");
        this.audit = Objects.requireNonNull(audit, "audit");
        PasswordPolicy policy = new PassPolicy();
        this.users = new MySqlUserRepository();
        // The session outlives the screen's idle timer by a minute, so the idle
        // logout (with its message) always happens first.
        this.auth = new AuthenticationService(users,
                new InMemorySessionManager(config.idleTimeout().plusMinutes(1)), policy, audit);
        this.authz = new AuthorizationService();
        this.accounts = new UserAccountService(auth, users, policy, audit);
        this.admin = new UserAdminService(auth, users, authz, policy, audit);
        this.sales = new MySqlSaleRepository();
        this.payments = new PaymentService();
        this.products = products;
        this.salesService = (products == null || stock == null) ? null
                : new SalesService(products, new PricingService(), config.terminalId(),
                        new CheckoutService(sales, new InvoiceNumberGenerator(), stock, audit));
        this.receipts = new ReceiptBuilder(config.store(), this::userName);
        this.printer = new JavaFxReceiptPrinter(config.receiptWidth());
    }

    /**
     * The standard wiring, with the audit log printed to the console.
     *
     * @param products Product &amp; Inventory's ProductLookup, or null if not connected yet
     * @param stock    Product &amp; Inventory's StockRecorder, or null if not connected yet
     */
    public static AppContext create(AppConfig config, ProductLookup products, StockRecorder stock) {
        return create(config, products, stock, (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId));
    }

    /** As above with a different audit log (e.g. a database-backed one later). */
    public static AppContext create(AppConfig config, ProductLookup products, StockRecorder stock,
                                    AuditLogger audit) {
        return new AppContext(config, audit, products, stock);
    }

    public AppConfig config() {
        return config;
    }

    public AuditLogger audit() {
        return audit;
    }

    public AuthenticationService auth() {
        return auth;
    }

    public AuthorizationService authz() {
        return authz;
    }

    /** Change own password. */
    public UserAccountService accounts() {
        return accounts;
    }

    /** Admin: add / edit / activate users. */
    public UserAdminService admin() {
        return admin;
    }

    /** Completed sales, read-only (Reports, receipt reprint). */
    public SaleHistory saleHistory() {
        return sales;
    }

    /** Completed sales not yet synced (Sync). */
    public SaleExport saleExport() {
        return sales;
    }

    public PaymentService payments() {
        return payments;
    }

    /** Makes receipts from saved sales, with this terminal's store details. */
    public ReceiptBuilder receipts() {
        return receipts;
    }

    /** Prints receipts (the default printer). */
    public PrinterPort receiptPrinter() {
        return printer;
    }

    /**
     * The person's name for a user ID, e.g. "Juan Dela Cruz"; null if unknown.
     *
     * @throws RuntimeException database problem
     */
    public String userName(String userId) {
        auth.contract.User user = users.findById(userId);
        return user == null ? null : user.getFullName();
    }

    /** Product &amp; Inventory's lookup, if connected. */
    public Optional<ProductLookup> products() {
        return Optional.ofNullable(products);
    }

    /** This terminal's sale engine; empty until Product &amp; Inventory is connected. */
    public Optional<SalesService> salesService() {
        return Optional.ofNullable(salesService);
    }

    /** True when POS can ring up sales (products and stock-out are connected). */
    public boolean posAvailable() {
        return salesService != null;
    }

    /**
     * Adds a module's own service, e.g. {@code app.register(InventoryService.class, inv)}.
     *
     * @throws IllegalStateException that type is already registered
     */
    public <T> void register(Class<T> type, T service) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        if (services.putIfAbsent(type, service) != null) {
            throw new IllegalStateException(type.getSimpleName() + " is already registered");
        }
    }

    /** A service added with {@link #register}, if any. */
    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(services.get(type)));
    }
}
