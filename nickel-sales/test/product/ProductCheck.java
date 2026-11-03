package product;

import auth.contract.AuditLogger;
import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import auth.permission.AuthorizationService;
import inventory.contract.InsufficientStockException;
import pos.cart.CartException;
import pos.cart.PricingService;
import pos.cart.Sale;
import pos.cart.SaleLineItem;
import pos.cart.SalesService;
import pos.checkout.CheckoutService;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.SaleRecord;
import pos.repository.InvoiceNumberGenerator;
import pos.repository.MySqlSaleRepository;
import product.contract.ProductInfo;
import product.model.Category;
import product.model.Product;
import product.model.ProductDraft;
import product.model.Supplier;
import product.repository.DuplicateValueException;
import product.repository.MySqlCategoryRepository;
import product.repository.MySqlProductRepository;
import product.repository.MySqlSupplierRepository;
import product.service.CategoryService;
import product.service.ProductException;
import product.service.ProductService;
import product.service.SupplierService;
import shared.Database;
import shared.Money;
import stubs.StubStockRecorder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SELF-TEST for the Product module and its data layer: the model rules, the MySQL
 * repositories, ProductService / CategoryService / SupplierService, the seed
 * catalog, and POS finding and selling REAL products through ProductService.
 *
 * Needs the database set up (database/NickelSales-Database-Config.sql); the
 * database checks are skipped, not failed, when MySQL is not reachable.
 * Everything the checks create is named "ZZTEST ..." / "ZZ Test ..." and is
 * deleted at the start and at the end, so the seed products are never changed.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" product.ProductCheck
 * (macOS/Linux: ':' instead of ';'). Exit code 1 on failure.
 */
public class ProductCheck {

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    static final String PAPER = "4800000000019";   // seed: Bond Paper A4, 250.00 / 235.00, stock 40
    static final String RICE = "4800000000040";    // seed: VAT-exempt, by kg
    static final String BALLPEN = "4800000000026"; // seed: 12.00 / 10.00
    static final String CASHIER = "NSC-00002";

    static final List<String> auditLog = Collections.synchronizedList(new ArrayList<>());
    static final AuditLogger audit = (event, userId) -> auditLog.add(event + " by " + userId);

    static final User ADMIN = user("T-ADMIN", "A");
    static final User INVENTORY = user("T-INV", "I");
    static final User CASHIER_USER = user("T-CASH", "C");
    static final User LOCKED_ADMIN = new User("T-LOCK", "tlock", "x", "Test", "Locked",
            UserRole.parseDbRoles("A"), UserStatus.LOCKED);

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Product module checks (data layer + services)");
        System.out.println("=================================================================");

        // ---------------------------- no database needed ----------------------------
        check("Product model: required fields, trimming, price rules", () -> {
            Product p = new Product(" P-0100 ", "  ", " Test Item ", " pc ", Money.of("10.005"), null, true, true,
                    null, null, null, null, null);
            eq(p.productId(), "P-0100");
            isNull(p.barcode(), "a blank barcode becomes null");
            eq(p.name(), "Test Item");
            eq(p.unit(), "pc");
            eq(p.retailPrice().toPlainString(), "10.01");   // rounded half-up to 2 places
            eq(p.stockOnHand().toPlainString(), "0.000");   // no stock given = zero
            refused("name is required", () -> new Product("P-1", null, " ", "pc", Money.of("1"), null, true, true, null, null, null, null, null));
            refused("unit is required", () -> new Product("P-1", null, "A", " ", Money.of("1"), null, true, true, null, null, null, null, null));
            refused("retail price is required", () -> new Product("P-1", null, "A", "pc", null, null, true, true, null, null, null, null, null));
            refused("negative retail price", () -> new Product("P-1", null, "A", "pc", Money.of("-1"), null, true, true, null, null, null, null, null));
            refused("negative wholesale price", () -> new Product("P-1", null, "A", "pc", Money.of("1"), Money.of("-1"), true, true, null, null, null, null, null));
            refused("barcode with a space", () -> new Product("P-1", "48 00", "A", "pc", Money.of("1"), null, true, true, null, null, null, null, null));
            refused("price too large", () -> new Product("P-1", null, "A", "pc", new BigDecimal("99999999999.00"), null, true, true, null, null, null, null, null));
            refused("name too long", () -> new Product("P-1", null, "x".repeat(101), "pc", Money.of("1"), null, true, true, null, null, null, null, null));
        });

        check("Category and Supplier model rules", () -> {
            refused("category name required", () -> new Category(null, "  ", true));
            eq(new Category(null, "  Toys ", true).name(), "Toys");
            refused("supplier name required", () -> new Supplier(null, "", null, null, null, null, true));
            refused("supplier email must contain @", () -> new Supplier(null, "S", null, null, "nope", null, true));
            Supplier s = new Supplier(null, "S", " ", " ", " ", " ", true);
            isNull(s.contactPerson(), "blank optional fields become null");
            isNull(s.email(), "blank email becomes null");
        });

        // ------------------------------ needs MySQL ------------------------------
        boolean db = databaseReachable();
        String terminal = "PK" + (10000 + (int) (Math.random() * 90000));
        if (db) {
            cleanUp(terminal);
        }
        MySqlProductRepository productRepo = new MySqlProductRepository();
        MySqlCategoryRepository categoryRepo = new MySqlCategoryRepository();
        MySqlSupplierRepository supplierRepo = new MySqlSupplierRepository();
        AuthorizationService authz = new AuthorizationService();
        ProductService products = new ProductService(productRepo, categoryRepo, supplierRepo, authz, audit);
        CategoryService categories = new CategoryService(categoryRepo, authz, audit);
        SupplierService suppliers = new SupplierService(supplierRepo, authz, audit);

        try {
            check("Seed catalog: POS lookup by barcode and ID returns the real prices and flags", db, () -> {
                ProductInfo paper = products.findByBarcode(PAPER).orElseThrow();
                eq(paper.name(), "Bond Paper A4 (ream)");
                eq(paper.retailPrice().toPlainString(), "250.00");
                eq(paper.wholesalePrice().toPlainString(), "235.00");
                yes(paper.vatable() && paper.active(), "vatable and active");
                eq(products.findByBarcode("  " + PAPER + " ").orElseThrow().productId(), "P-0001");   // trimmed
                ProductInfo rice = products.findByBarcode(RICE).orElseThrow();
                yes(!rice.vatable(), "rice is VAT-exempt");
                eq(rice.stockOnHand().toPlainString(), "100.500");   // by-weight stock keeps 3 decimals
                yes(!products.findById("P-0006").orElseThrow().active(), "Desk Calculator is inactive");
                isNull(productRepo.findById("P-0007").orElseThrow().barcode(), "Glue Stick has no barcode");
                yes(productRepo.findById("P-0003").orElseThrow().wholesalePrice() == null, "Notebook has no wholesale price");
                yes(products.findByBarcode("0000000000000").isEmpty(), "unknown barcode");
                yes(products.findByBarcode(null).isEmpty() && products.findByBarcode(" ").isEmpty(), "blank barcode");
                yes(products.findById("P-9999").isEmpty(), "unknown ID");
            });

            check("Cashier search: name or barcode, active only, sorted, limited, % is plain text", db, () -> {
                List<ProductInfo> tea = products.search("TEA", 10);
                yes(tea.stream().anyMatch(p -> p.productId().equals("P-0008")), "finds Oolong Tea ignoring case");
                yes(products.search("4800000000026", 10).stream().anyMatch(p -> p.productId().equals("P-0002")), "finds by barcode");
                yes(products.search("calculator", 10).isEmpty(), "inactive products are not offered");
                List<ProductInfo> all = products.search("", 100);
                yes(all.size() >= 8, "blank text lists the active products");
                for (int i = 1; i < all.size(); i++) {
                    yes(all.get(i - 1).name().compareToIgnoreCase(all.get(i).name()) <= 0, "sorted by name");
                }
                eq(String.valueOf(products.search("", 3).size()), "3");
                eq(String.valueOf(products.search("", 0).size()), "0");
                yes(products.search("%", 10).isEmpty(), "% matches only a real percent sign");
                yes(products.search("_", 10).isEmpty(), "_ matches only a real underscore");
            });

            check("Categories: create, duplicate (any case) refused, rename, deactivate", db, () -> {
                Category toys = categories.createCategory(ADMIN, "  ZZ Test Toys ");
                yes(toys.categoryId() != null, "new ID");
                eq(toys.name(), "ZZ Test Toys");
                refusedWith("already exists", () -> categories.createCategory(INVENTORY, "zz test TOYS"));
                refusedWith("required", () -> categories.createCategory(ADMIN, " "));
                Category renamed = categories.renameCategory(ADMIN, toys.categoryId(), "ZZ Test Games");
                eq(renamed.name(), "ZZ Test Games");
                eq(categoryRepo.findById(toys.categoryId()).orElseThrow().name(), "ZZ Test Games");
                categories.renameCategory(ADMIN, toys.categoryId(), "ZZ Test Games");   // same name is fine
                categories.setActive(ADMIN, toys.categoryId(), false);
                yes(listNames(categories.listCategories(ADMIN, false)).stream().noneMatch("ZZ Test Games"::equals), "hidden from active list");
                yes(listNames(categories.listCategories(ADMIN, true)).contains("ZZ Test Games"), "still in the full list");
                categories.setActive(ADMIN, toys.categoryId(), true);
                yes(listNames(categories.listCategories(ADMIN, false)).contains("ZZ Test Games"), "active again");
                yes(listNames(categories.listCategories(ADMIN, false)).contains("Office Supplies"), "seed categories exist");
            });

            check("Suppliers: create with details, update, duplicate refused, deactivate", db, () -> {
                Supplier s = suppliers.createSupplier(ADMIN, "ZZ Test Supplier", "Ana Cruz", "0917-000-0000",
                        "ana@example.com", "1 Test St");
                Supplier read = supplierRepo.findById(s.supplierId()).orElseThrow();
                eq(read.contactPerson(), "Ana Cruz");
                eq(read.email(), "ana@example.com");
                refusedWith("already exists", () -> suppliers.createSupplier(ADMIN, "zz test supplier", null, null, null, null));
                refusedWith("email", () -> suppliers.createSupplier(ADMIN, "ZZ Test Other", null, null, "bad", null));
                suppliers.updateSupplier(ADMIN, s.supplierId(), "ZZ Test Supplier", "Ben Lim", null, null, null);
                Supplier changed = supplierRepo.findById(s.supplierId()).orElseThrow();
                eq(changed.contactPerson(), "Ben Lim");
                isNull(changed.phone(), "cleared phone is stored as null");
                suppliers.setActive(ADMIN, s.supplierId(), false);
                yes(!supplierRepo.findById(s.supplierId()).orElseThrow().active(), "deactivated");
                yes(suppliers.listSuppliers(ADMIN, false).stream().noneMatch(x -> x.name().equals("ZZ Test Supplier")), "hidden");
                yes(suppliers.listSuppliers(ADMIN, true).stream().anyMatch(x -> x.name().equals("ZZ Test Supplier")), "in the full list");
            });

            check("Create product: next ID, every field saved in MySQL, stock starts at zero", db, () -> {
                Category cat = categoryRepo.findByName("ZZ Test Games").orElseThrow();
                Supplier sup = supplierRepo.findByName("ZZ Test Supplier").orElseThrow();
                categories.setActive(ADMIN, cat.categoryId(), true);
                suppliers.setActive(ADMIN, sup.supplierId(), true);
                String expectedId = productRepo.nextProductId();
                Product created = products.createProduct(INVENTORY, new ProductDraft(
                        "ZZTEST-0001", "ZZTEST Widget", "box", Money.of("99.50"), Money.of("80"), true,
                        cat.categoryId(), sup.supplierId()));
                eq(created.productId(), expectedId);
                yes(expectedId.matches("P-\\d{4,}"), "ID looks like P-0010");
                // Read it back through a brand-new repository: it really is in MySQL.
                Product stored = new MySqlProductRepository().findById(created.productId()).orElseThrow();
                eq(stored.barcode(), "ZZTEST-0001");
                eq(stored.name(), "ZZTEST Widget");
                eq(stored.unit(), "box");
                eq(stored.retailPrice().toPlainString(), "99.50");
                eq(stored.wholesalePrice().toPlainString(), "80.00");
                yes(stored.vatable() && stored.active(), "vatable and active");
                eq(stored.categoryName(), "ZZ Test Games");
                eq(stored.supplierName(), "ZZ Test Supplier");
                eq(stored.stockOnHand().toPlainString(), "0.000");
                // No wholesale price, no category, no supplier, no barcode, VAT-exempt.
                Product plain = products.createProduct(ADMIN, new ProductDraft(
                        " ", "ZZTEST Plain", "kg", Money.of("5"), null, false, null, null));
                Product plainStored = productRepo.findById(plain.productId()).orElseThrow();
                isNull(plainStored.barcode(), "no barcode stored as NULL");
                yes(plainStored.wholesalePrice() == null, "no wholesale price stored as NULL");
                yes(!plainStored.vatable(), "VAT-exempt stored");
                yes(plainStored.categoryId() == null && plainStored.supplierId() == null, "no category or supplier");
                // Two products without a barcode are fine (NULLs are not duplicates).
                products.createProduct(ADMIN, new ProductDraft(null, "ZZTEST Plain 2", "pc", Money.of("1"), null, true, null, null));
                yes(!plain.productId().equals(created.productId()), "different IDs");
                yes(auditLog.stream().anyMatch(e -> e.startsWith("PRODUCT_CREATED target=" + created.productId())), "creation is audited");
            });

            check("Create product: rules (barcode, prices, category, supplier, permission)", db, () -> {
                Category cat = categoryRepo.findByName("ZZ Test Games").orElseThrow();
                refusedWith("already used by \"ZZTEST Widget\"", () -> products.createProduct(ADMIN, draft("ZZTEST-0001", "Another", "5", null)));
                refusedWith("wholesale price cannot be higher", () -> products.createProduct(ADMIN, draft("ZZTEST-0002", "Bad", "5", "6")));
                refusedWith("name is required", () -> products.createProduct(ADMIN, draft("ZZTEST-0003", " ", "5", null)));
                refusedWith("retail price is required", () -> products.createProduct(ADMIN, new ProductDraft(
                        "ZZTEST-0004", "No price", "pc", null, null, true, null, null)));
                refusedWith("no longer exists", () -> products.createProduct(ADMIN, new ProductDraft(
                        "ZZTEST-0005", "Ghost cat", "pc", Money.of("1"), null, true, 999999, null)));
                refusedWith("no longer exists", () -> products.createProduct(ADMIN, new ProductDraft(
                        "ZZTEST-0006", "Ghost sup", "pc", Money.of("1"), null, true, null, 999999)));
                categories.setActive(ADMIN, cat.categoryId(), false);
                refusedWith("is deactivated", () -> products.createProduct(ADMIN, new ProductDraft(
                        "ZZTEST-0007", "Hidden cat", "pc", Money.of("1"), null, true, cat.categoryId(), null)));
                categories.setActive(ADMIN, cat.categoryId(), true);
                refusedWith("permission", () -> products.createProduct(CASHIER_USER, draft("ZZTEST-0008", "Nope", "5", null)));
                refusedWith("permission", () -> products.createProduct(LOCKED_ADMIN, draft("ZZTEST-0009", "Nope", "5", null)));
                refusedWith("log in again", () -> products.createProduct(null, draft("ZZTEST-0010", "Nope", "5", null)));
                yes(products.listProducts(ADMIN, "ZZTEST-000", true).stream()
                        .noneMatch(p -> !"ZZTEST-0001".equals(p.barcode())), "none of the refused products were saved");
            });

            check("Edit product: prices persist, stock is never touched, own barcode is allowed", db, () -> {
                Product widget = productRepo.findByBarcode("ZZTEST-0001").orElseThrow();
                setStockAsInventory(widget.productId(), "25.500");   // Inventory adds stock
                Category cat = categoryRepo.findByName("ZZ Test Games").orElseThrow();
                Supplier sup = supplierRepo.findByName("ZZ Test Supplier").orElseThrow();
                Product edited = products.updateProduct(INVENTORY, widget.productId(), new ProductDraft(
                        "ZZTEST-0001", "ZZTEST Widget Deluxe", "pc", Money.of("120"), Money.of("100.255"), false,
                        cat.categoryId(), sup.supplierId()));
                eq(edited.name(), "ZZTEST Widget Deluxe");
                Product stored = new MySqlProductRepository().findById(widget.productId()).orElseThrow();
                eq(stored.unit(), "pc");
                eq(stored.retailPrice().toPlainString(), "120.00");
                eq(stored.wholesalePrice().toPlainString(), "100.26");   // rounded half-up
                yes(!stored.vatable(), "VAT flag changed");
                eq(stored.stockOnHand().toPlainString(), "25.500");      // untouched by the edit
                yes(stored.active(), "active status kept");
                // Remove the wholesale price and the category.
                products.updateProduct(ADMIN, widget.productId(), new ProductDraft(
                        "ZZTEST-0001", "ZZTEST Widget Deluxe", "pc", Money.of("120"), null, true, null, null));
                Product cleared = productRepo.findById(widget.productId()).orElseThrow();
                yes(cleared.wholesalePrice() == null && cleared.categoryId() == null, "cleared values are stored as NULL");
                eq(cleared.stockOnHand().toPlainString(), "25.500");
                // Taking another product's barcode is refused.
                refusedWith("already used by", () -> products.updateProduct(ADMIN, widget.productId(), draft(PAPER, "ZZTEST Widget Deluxe", "5", null)));
                refusedWith("no longer exists", () -> products.updateProduct(ADMIN, "P-NOPE", draft("ZZTEST-0020", "x", "5", null)));
                refusedWith("permission", () -> products.updateProduct(CASHIER_USER, widget.productId(), draft("ZZTEST-0001", "x", "5", null)));
                // A product may keep a category that was deactivated after it was assigned.
                products.updateProduct(ADMIN, widget.productId(), new ProductDraft(
                        "ZZTEST-0001", "ZZTEST Widget Deluxe", "pc", Money.of("120"), null, true, cat.categoryId(), null));
                categories.setActive(ADMIN, cat.categoryId(), false);
                products.updateProduct(ADMIN, widget.productId(), new ProductDraft(
                        "ZZTEST-0001", "ZZTEST Widget Renamed", "pc", Money.of("121"), null, true, cat.categoryId(), null));
                eq(productRepo.findById(widget.productId()).orElseThrow().name(), "ZZTEST Widget Renamed");
                categories.setActive(ADMIN, cat.categoryId(), true);
                yes(auditLog.stream().anyMatch(e -> e.startsWith("PRODUCT_UPDATED target=" + widget.productId())), "edit is audited");
            });

            check("Deactivate / reactivate: POS still finds it but flags it; search hides it", db, () -> {
                Product widget = productRepo.findByBarcode("ZZTEST-0001").orElseThrow();
                products.setActive(ADMIN, widget.productId(), false);
                ProductInfo info = products.findByBarcode("ZZTEST-0001").orElseThrow();
                yes(!info.active(), "POS sees it as no longer sold");
                yes(products.search("ZZTEST Widget", 10).isEmpty(), "cashier search hides it");
                yes(products.listProducts(ADMIN, "ZZTEST Widget", false).isEmpty(), "active-only list hides it");
                yes(products.listProducts(ADMIN, "ZZTEST Widget", true).size() == 1, "full list shows it");
                products.setActive(ADMIN, widget.productId(), false);   // already inactive: nothing happens
                products.setActive(ADMIN, widget.productId(), true);
                yes(products.findByBarcode("ZZTEST-0001").orElseThrow().active(), "sold again");
                yes(products.search("ZZTEST Widget", 10).size() == 1, "cashier search shows it again");
                refusedWith("permission", () -> products.setActive(CASHIER_USER, widget.productId(), false));
                yes(auditLog.stream().anyMatch(e -> e.startsWith("PRODUCT_DEACTIVATED")), "deactivation is audited");
            });

            check("MySQL itself refuses duplicate barcodes / IDs and negative stock (two terminals racing)", db, () -> {
                Product base = products.createProduct(ADMIN, draft("ZZTEST-0040", "ZZTEST Race", "5", null));
                // Bypass the service: go straight to the repository, as a second terminal would.
                try {
                    productRepo.insert(Product.create("P-8888", draft("ZZTEST-0040", "ZZTEST Race Twin", "5", null)));
                    throw new AssertionError("the database must refuse a duplicate barcode");
                } catch (DuplicateValueException e) {
                    yes(e.isKey("barcode"), "the barcode index is named (was \"" + e.getKey() + "\")");
                }
                try {
                    productRepo.insert(Product.create(base.productId(), draft("ZZTEST-0041", "ZZTEST Same ID", "5", null)));
                    throw new AssertionError("the database must refuse a duplicate product ID");
                } catch (DuplicateValueException e) {
                    yes(e.isKey("primary"), "the primary key is named (was \"" + e.getKey() + "\")");
                }
                try {
                    setStockAsInventory(base.productId(), "-1");
                    throw new AssertionError("the database must refuse negative stock");
                } catch (SQLException expected) {
                    // refused by the CHECK constraint
                }
                // Two terminals pick the same new ID at the same moment: the service tries again.
                int[] calls = {0};
                MySqlProductRepository racing = new MySqlProductRepository() {
                    @Override
                    public String nextProductId() {
                        return calls[0]++ == 0 ? base.productId() : super.nextProductId();   // first answer is stale
                    }
                };
                ProductService racingService = new ProductService(racing, categoryRepo, supplierRepo, authz, audit);
                Product second = racingService.createProduct(ADMIN, draft("ZZTEST-0042", "ZZTEST Second", "5", null));
                yes(!second.productId().equals(base.productId()), "the second product got a different ID");
                yes(calls[0] >= 2, "it asked for a new ID after the clash");
                eq(productRepo.findById(base.productId()).orElseThrow().name(), "ZZTEST Race");   // the first one is intact
            });

            check("POS reads real products: price, wholesale, VAT-exempt, inactive and out-of-stock refusals", db, () -> {
                SalesService pos = new SalesService(products, terminal);
                pos.startSale(CASHIER);
                SaleLineItem line = pos.addItem(PAPER);
                eq(line.unitPrice().toPlainString(), "250.00");
                pos.addItem(PAPER, new BigDecimal("4"));            // 5 in total: wholesale threshold of the store rule
                pos.addItem(RICE, new BigDecimal("1.5"));           // by weight
                // Out of stock: a product with no stock is not added.
                Product empty = products.createProduct(ADMIN, draft("ZZTEST-0030", "ZZTEST Empty", "10", null));
                try {
                    pos.addItem("ZZTEST-0030");
                    throw new AssertionError("a product with no stock must not be added");
                } catch (InsufficientStockException | CartException expected) {
                    // refused, as POS promises
                }
                // Inactive: refused.
                products.setActive(ADMIN, empty.productId(), false);
                setStockAsInventory(empty.productId(), "5");
                try {
                    pos.addItem("ZZTEST-0030");
                    throw new AssertionError("an inactive product must not be added");
                } catch (InsufficientStockException | CartException expected) {
                    // refused
                }
                // Search then add by ID (a product without a barcode).
                ProductInfo glue = products.search("glue", 5).get(0);
                pos.addProduct(glue.productId());
                yes(pos.getTotal().signum() > 0, "the cart has a total");
            });

            check("Checkout on real products: stock really goes down in MySQL; short stock is refused", db, () -> {
                Product item = productRepo.findByBarcode("ZZTEST-0001").orElseThrow();
                setStockAsInventory(item.productId(), "10");
                MySqlSaleRepository sales = new MySqlSaleRepository();
                CheckoutService checkout = new CheckoutService(sales, new InvoiceNumberGenerator(),
                        new StubStockRecorder(), audit);
                SalesService pos = new SalesService(products, new PricingService(), terminal, checkout);
                Sale sale = pos.startSale(CASHIER);
                pos.addItem("ZZTEST-0001", new BigDecimal("3"));
                PaymentService payments = new PaymentService();
                SaleRecord rec = pos.finalizeSale(payments.recordPayment(sale, PaymentMethod.CASH, Money.of("1000"), null));
                yes(rec.invoiceNo().startsWith(terminal + "-"), "invoice number uses the terminal");
                eq(stockOf(item.productId()).toPlainString(), "7.000");
                // 8 more than the 7 left: refused, and stock stays at 7.
                Sale second = pos.startSale(CASHIER);
                pos.addItem("ZZTEST-0001", new BigDecimal("7"));
                // Someone else (another terminal) takes 5 before this sale is paid:
                setStockAsInventory(item.productId(), "2");
                try {
                    pos.finalizeSale(payments.recordPayment(second, PaymentMethod.CASH, Money.of("5000"), null));
                    throw new AssertionError("the sale must be refused when stock ran short");
                } catch (CartException | pos.checkout.CheckoutException expected) {
                    // refused
                }
                eq(stockOf(item.productId()).toPlainString(), "2.000");
            });
        } finally {
            if (db) {
                cleanUp(terminal);
            }
        }

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed, " + skipped + " skipped"
                + (db ? "" : " (MySQL not reachable)"));
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------- helpers ------------------------------- */

    static User user(String id, String roles) {
        return new User(id, id.toLowerCase(), "x", "Test", "User", UserRole.parseDbRoles(roles), UserStatus.ACTIVE);
    }

    static ProductDraft draft(String barcode, String name, String retail, String wholesale) {
        return new ProductDraft(barcode, name, "pc", Money.of(retail), wholesale == null ? null : Money.of(wholesale),
                true, null, null);
    }

    static List<String> listNames(List<Category> list) {
        return list.stream().map(Category::name).toList();
    }

    static boolean databaseReachable() {
        try (Connection c = Database.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /** What the Inventory module does: sets the stock figure directly (in the Inventory table). */
    static void setStockAsInventory(String productId, String qty) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO Inventory (ProductID, QuantityOnHand, ReorderLevel, UpdatedAt) "
                             + "VALUES (?, ?, 0, UTC_TIMESTAMP(3)) "
                             + "ON DUPLICATE KEY UPDATE QuantityOnHand = VALUES(QuantityOnHand)")) {
            ps.setString(1, productId);
            ps.setBigDecimal(2, new BigDecimal(qty));
            ps.executeUpdate();
        }
    }

    static BigDecimal stockOf(String productId) throws SQLException {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(QuantityOnHand), 0) FROM Inventory WHERE ProductID = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Deletes everything these checks create (test sales first, then products, then categories/suppliers). */
    static void cleanUp(String terminal) {
        String sub = "SELECT SaleID FROM Sale WHERE TerminalID = ?";
        String[][] statements = {
                {"DELETE FROM Payment WHERE SaleID IN (SELECT * FROM (" + sub + ") s)", terminal},
                {"DELETE FROM SaleLine WHERE SaleID IN (SELECT * FROM (" + sub + ") s)", terminal},
                {"DELETE FROM Sale WHERE TerminalID = ?", terminal},
                {"DELETE FROM InvoiceSequence WHERE TerminalID = ?", terminal},
                {"DELETE FROM Product WHERE Barcode LIKE ? OR ProductName LIKE ?", "ZZTEST%"},
                {"DELETE FROM Inventory WHERE ProductID NOT IN (SELECT ProductID FROM Product)", "x"},
                {"DELETE FROM Category WHERE CategoryName LIKE ?", "ZZ Test%"},
                {"DELETE FROM Supplier WHERE SupplierName LIKE ?", "ZZ Test%"}};
        try (Connection c = Database.getConnection()) {
            for (String[] st : statements) {
                try (PreparedStatement ps = c.prepareStatement(st[0])) {
                    long marks = st[0].chars().filter(ch -> ch == '?').count();
                    for (int i = 1; i <= marks; i++) {
                        ps.setString(i, st[1]);   // every ? in these statements takes the same value
                    }
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.out.println(" [WARN] could not clean up test data: " + e);
        }
    }

    interface Body {
        void run() throws Exception;
    }

    /** A check that needs no database. */
    static void check(String name, Body body) {
        run(name, body);
    }

    /** A check that needs MySQL: skipped, not failed, when it is not reachable. */
    static void check(String name, boolean dbReachable, Body body) {
        if (!dbReachable) {
            skipped++;
            System.out.println(" [SKIP] " + name);
            return;
        }
        run(name, body);
    }

    private static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        }
    }

    static void eq(String actual, String expected) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }

    static void yes(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void isNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + " (was " + value + ")");
        }
    }

    /** The code must throw IllegalArgumentException (a model rule). */
    static void refused(String what, Runnable code) {
        try {
            code.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("should have been refused: " + what);
    }

    interface ServiceCall {
        void run() throws Exception;
    }

    /** The code must throw a ProductException whose message contains {@code part}. */
    static void refusedWith(String part, ServiceCall code) {
        try {
            code.run();
        } catch (ProductException e) {
            if (!e.getMessage().contains(part)) {
                throw new AssertionError("message \"" + e.getMessage() + "\" should contain \"" + part + "\"");
            }
            return;
        } catch (Exception e) {
            throw new AssertionError("expected a ProductException but got " + e);
        }
        throw new AssertionError("should have been refused (\"" + part + "\")");
    }

}
