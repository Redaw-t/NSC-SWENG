package product.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import auth.permission.AuthorizationService;
import product.contract.BarcodeEntry;
import product.contract.ProductInfo;
import product.contract.ProductLookup;
import product.model.Category;
import product.model.Product;
import product.model.ProductDraft;
import product.model.Supplier;
import product.repository.CategoryRepository;
import product.repository.DuplicateValueException;
import product.repository.ProductRepository;
import product.repository.SupplierRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The product catalog's use cases and rules.
 *
 * <p><b>For POS</b> (and Barcode): implements {@link ProductLookup}. The three
 * lookup methods need no login, never throw a checked exception, and read straight
 * from MySQL through {@link ProductRepository}, so a price or stock change is seen
 * by the very next scan.
 *
 * <p><b>For the Products screen</b>: list, create, edit and deactivate / reactivate.
 * Each of those takes the acting {@link User} and needs MANAGE_PRODUCTS. Rules:
 * the name, unit and retail price are required; prices are not negative and the
 * wholesale price is not above the retail price; a barcode belongs to one product
 * only; a new product can only use an active category and supplier (an edit may
 * keep a deactivated one it already has). Products are never deleted.
 *
 * <p><b>Stock</b> is not managed here. A new product starts at zero, editing never
 * touches the stock figure, and there is no method to change it: that is the
 * Inventory module's job.
 */
public class ProductService implements ProductLookup {

    /** Tries to take the next ID this many times if another terminal takes it first. */
    private static final int ID_ATTEMPTS = 5;
    /** The most products the management list loads at once. */
    private static final int LIST_LIMIT = 10_000;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final SupplierRepository suppliers;
    private final CatalogAccess access;

    public ProductService(ProductRepository products, CategoryRepository categories,
                          SupplierRepository suppliers, AuthorizationService authz, AuditLogger audit) {
        this.products = Objects.requireNonNull(products, "products");
        this.categories = Objects.requireNonNull(categories, "categories");
        this.suppliers = Objects.requireNonNull(suppliers, "suppliers");
        this.access = new CatalogAccess(authz, audit);
    }

    /* ----------------------------- for POS ----------------------------- */

    @Override
    public Optional<ProductInfo> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        return products.findByBarcode(barcode.trim()).map(Product::toInfo);
    }

    @Override
    public Optional<ProductInfo> findById(String productId) {
        if (productId == null || productId.isBlank()) {
            return Optional.empty();
        }
        return products.findById(productId.trim()).map(Product::toInfo);
    }

    @Override
    public List<ProductInfo> search(String text, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return products.search(text, true, limit).stream().map(Product::toInfo).toList();
    }

    /* ------------------------- for the Products screen ------------------------- */

    /**
     * Every product (or only the sold ones) sorted by name, for the Products screen.
     *
     * @param text            blank = all; otherwise names or barcodes containing it
     * @param includeInactive true to include discontinued products
     */
    public List<Product> listProducts(User actor, String text, boolean includeInactive) throws ProductException {
        access.require(actor);
        return products.search(text, !includeInactive, LIST_LIMIT);
    }

    public Product getProduct(User actor, String productId) throws ProductException {
        access.require(actor);
        return requireProduct(productId);
    }

    /** Creates an active product with the next free ID (P-0001, P-0002, ...) and no stock. */
    public Product createProduct(User actor, ProductDraft draft) throws ProductException {
        access.require(actor);
        Objects.requireNonNull(draft, "draft");
        for (int attempt = 1; attempt <= ID_ATTEMPTS; attempt++) {
            Product candidate = build(() -> Product.create(products.nextProductId(), draft));
            checkRules(candidate, null);
            try {
                products.insert(candidate);
                access.log("PRODUCT_CREATED", actor, candidate.productId());
                return products.findById(candidate.productId()).orElse(candidate);
            } catch (DuplicateValueException e) {
                if (e.isKey("barcode")) {
                    throw new ProductException(barcodeTaken(candidate.barcode()));
                }
                // Another terminal took this ID a moment ago: look again for the next free one.
            }
        }
        throw new ProductException("The product could not be saved because other terminals are adding "
                + "products at the same time. Try again.");
    }

    /** Changes a product's details. Its ID, active status and stock are kept. */
    public Product updateProduct(User actor, String productId, ProductDraft draft) throws ProductException {
        access.require(actor);
        Objects.requireNonNull(draft, "draft");
        Product existing = requireProduct(productId);
        Product changed = build(() -> existing.withDraft(draft));
        checkRules(changed, existing);
        try {
            products.update(changed);
        } catch (DuplicateValueException e) {
            throw new ProductException(barcodeTaken(changed.barcode()));
        }
        access.log("PRODUCT_UPDATED", actor, changed.productId());
        String prices = priceChanges(existing, changed);
        if (!prices.isEmpty()) {
            access.log("PRICE_CHANGED", actor, changed.productId(), prices);   // the admin's price audit trail
        }
        return requireProduct(productId);
    }

    /** "retail=250.00>260.00 wholesale=none>240.00" for the prices that changed; empty if none did. */
    private static String priceChanges(Product before, Product after) {
        StringBuilder out = new StringBuilder();
        if (!samePrice(before.retailPrice(), after.retailPrice())) {
            out.append("retail=").append(price(before.retailPrice())).append('>').append(price(after.retailPrice()));
        }
        if (!samePrice(before.wholesalePrice(), after.wholesalePrice())) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append("wholesale=").append(price(before.wholesalePrice()))
                    .append('>').append(price(after.wholesalePrice()));
        }
        return out.toString();
    }

    private static boolean samePrice(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String price(BigDecimal p) {
        return p == null ? "none" : p.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
    
    /**
     * Every sold (active) product with the time its record last changed, sorted by name, for the
     * Barcode screen.
     */
    public List<BarcodeEntry> listBarcodeEntries(User actor) throws ProductException {
        access.require(actor);
        Map<String, LocalDateTime> updated = products.lastUpdated();
        return products.search("", true, LIST_LIMIT).stream()
                .map(p -> new BarcodeEntry(p.toInfo(), updated.get(p.productId())))
                .toList();
    }

    /**
     * Saves {@code barcode} on a product (replacing any it has) and changes nothing else about it.
     * A barcode belongs to one product only: if another product has it, the message names that product.
     */
    public void assignBarcode(User actor, String productId, String barcode) throws ProductException {
        access.require(actor);
        if (barcode == null || barcode.isBlank()) {
            throw new ProductException("Enter a barcode.");
        }
        Product existing = requireProduct(productId);
        Product changed = build(() -> existing.withBarcode(barcode));   // length / spaces rules
        checkRules(changed, existing);                                  // used by another product?
        try {
            products.updateBarcode(changed.productId(), changed.barcode());
        } catch (DuplicateValueException e) {
            throw new ProductException(barcodeTaken(changed.barcode()));   // another terminal was faster
        }
        access.log(existing.barcode() == null ? "PRODUCT_BARCODE_ASSIGNED" : "PRODUCT_BARCODE_REASSIGNED",
                actor, changed.productId());
    }

    /** Discontinues ({@code false}) or reactivates ({@code true}) a product. POS will not sell an inactive one. */
    public void setActive(User actor, String productId, boolean active) throws ProductException {
        access.require(actor);
        Product existing = requireProduct(productId);
        if (existing.active() == active) {
            return;
        }
        products.update(existing.withActive(active));
        access.log(active ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED", actor, existing.productId());
    }

    /* ------------------------------- rules ------------------------------- */

    private interface Builder {
        Product build();
    }

    /** Builds the product, turning a rule the model refuses into a ProductException. */
    private static Product build(Builder builder) throws ProductException {
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new ProductException(e.getMessage());
        }
    }

    /** Rules that need more than the product's own fields. {@code before} is null for a new product. */
    private void checkRules(Product p, Product before) throws ProductException {
        if (p.wholesalePrice() != null && p.wholesalePrice().compareTo(p.retailPrice()) > 0) {
            throw new ProductException("The wholesale price cannot be higher than the retail price.");
        }
        if (p.barcode() != null) {
            Optional<Product> sameBarcode = products.findByBarcode(p.barcode());
            if (sameBarcode.isPresent() && !sameBarcode.get().productId().equals(p.productId())) {
                throw new ProductException("The barcode " + p.barcode() + " is already used by \""
                        + sameBarcode.get().name() + "\" (" + sameBarcode.get().productId() + ").");
            }
        }
        if (p.categoryId() != null) {
            Category category = categories.findById(p.categoryId())
                    .orElseThrow(() -> new ProductException("That category no longer exists."));
            boolean unchanged = before != null && p.categoryId().equals(before.categoryId());
            if (!category.active() && !unchanged) {
                throw new ProductException("The category \"" + category.name() + "\" is deactivated.");
            }
        }
        if (p.supplierId() != null) {
            Supplier supplier = suppliers.findById(p.supplierId())
                    .orElseThrow(() -> new ProductException("That supplier no longer exists."));
            boolean unchanged = before != null && p.supplierId().equals(before.supplierId());
            if (!supplier.active() && !unchanged) {
                throw new ProductException("The supplier \"" + supplier.name() + "\" is deactivated.");
            }
        }
    }

    private Product requireProduct(String productId) throws ProductException {
        return products.findById(productId)
                .orElseThrow(() -> new ProductException("Product " + productId + " no longer exists."));
    }

    private static String barcodeTaken(String barcode) {
        return "The barcode " + barcode + " is already used by another product.";
    }
}
