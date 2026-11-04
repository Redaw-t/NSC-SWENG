package inventory.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import inventory.contract.InsufficientStockException;
import inventory.contract.StockLine;
import inventory.contract.StockOverview;
import inventory.contract.StockRecorder;
import inventory.contract.StockSnapshot;
import inventory.model.MovementType;
import inventory.model.StockLevel;
import inventory.model.StockMovement;
import inventory.model.StockReason;
import inventory.model.StockRow;
import inventory.model.StockStatus;
import inventory.repository.InventoryRepository;
import product.contract.ProductInfo;
import product.contract.ProductLookup;
import shared.Quantity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The Inventory use cases: stock in, stock out, adjustments, reorder levels,
 * and the stock-out of a completed sale ({@link StockRecorder}); read-only current stock for Reports ({@link StockOverview}).
 *
 * Rules enforced here (not in the screen):
 * - the caller must be logged in with UPDATE_INVENTORY (every screen method takes the session id);
 * - quantities are more than zero, at most 3 decimals, and stock never goes below zero;
 * - every change writes a StockMovement in the same transaction, so the history can never disagree with the stock;
 * - the product must exist in the Product module;
 * - stock-out and adjustments need a reason, and "Other" needs a note.
 *
 * Not here on purpose: StockLevels (see InventoryStockLevels, to avoid a construction cycle with Product).
 */
public class InventoryService implements StockRecorder, StockOverview {

    static final int MAX_NOTE_LENGTH = 200;
    private static final int MAX_DECIMALS = 3;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");
    private static final int LIST_LIMIT = 5000;

    private final InventoryRepository repository;
    private final ProductLookup products;
    private final InventoryAccess access;
    private final AuditLogger auditLogger;   // may be null

    public InventoryService(InventoryRepository repository, ProductLookup products,
                            InventoryAccess access, AuditLogger auditLogger) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.products = Objects.requireNonNull(products, "products");
        this.access = Objects.requireNonNull(access, "access");
        this.auditLogger = auditLogger;
    }

    // ---- read-only, for the Inventory screen ---------------------------------

    /**
     * Current Stock screen: every active product (plus any product that has a stock row)
     * with its stock and reorder level. A product never stocked shows 0 on hand.
     */
    public List<StockRow> listStock(String sessionId) throws InventoryException {
        access.require(sessionId);
        return stockRows();
    }

    /**
     * {@link StockOverview}: the same rows as {@link #listStock}, for the Reports module.
     * No session check here: Reports checks its own permission (VIEW_INVENTORY_REPORTS).
     */
    @Override
    public List<StockSnapshot> currentStock() {
        List<StockSnapshot> snapshots = new ArrayList<>();
        for (StockRow row : stockRows()) {
            snapshots.add(new StockSnapshot(row.productId(), row.name(), row.barcode(), row.unit(),
                    row.onHand(), row.reorderLevel(), levelOf(row.status())));
        }
        return snapshots;
    }

    /** A switch, not valueOf, so a new StockStatus is a compile error here rather than a runtime one. */
    private static StockSnapshot.Level levelOf(StockStatus status) {
        return switch (status) {
            case IN_STOCK -> StockSnapshot.Level.IN_STOCK;
            case LOW_STOCK -> StockSnapshot.Level.LOW_STOCK;
            case OUT_OF_STOCK -> StockSnapshot.Level.OUT_OF_STOCK;
        };
    }

    private List<StockRow> stockRows() {
        Map<String, StockLevel> levels = new HashMap<>();
        for (StockLevel level : repository.findAll()) {
            levels.put(level.productId(), level);
        }
        Map<String, ProductInfo> infos = new LinkedHashMap<>();
        for (ProductInfo p : products.search("", LIST_LIMIT)) {
            infos.put(p.productId(), p);
        }
        for (String id : levels.keySet()) {
            if (!infos.containsKey(id)) {
                products.findById(id).ifPresent(p -> infos.put(id, p));
            }
        }
        List<StockRow> rows = new ArrayList<>();
        for (ProductInfo p : infos.values()) {
            StockLevel level = levels.get(p.productId());
            rows.add(new StockRow(p.productId(), p.name(), p.barcode(), p.unit(),
                    level == null ? BigDecimal.ZERO : level.quantityOnHand(),
                    level == null ? BigDecimal.ZERO : level.reorderLevel(),
                    level == null ? null : level.updatedAt()));
        }
        rows.sort(Comparator.comparing(StockRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Category filter: product ID -> category name (products with no category are left out). */
    public Map<String, String> productCategories(String sessionId) throws InventoryException {
        access.require(sessionId);
        return repository.findProductCategories();
    }

    /** Category filter: the category names to offer, A-Z. */
    public List<String> categoryNames(String sessionId) throws InventoryException {
        access.require(sessionId);
        return repository.findCategoryNames();
    }

    /** Stock Movement History screen: newest first, at most {@code limit}. */
    public List<StockMovement> history(String sessionId, int limit) throws InventoryException {
        access.require(sessionId);
        return repository.findMovements(null, limit);
    }

    // ---- screen use cases ----------------------------------------------------

    /** Stock In window: adds {@code quantity}. {@code note} is the "Reference / Note" box (e.g. a PO number); may be blank. */
    public StockLevel stockIn(String sessionId, String productId, BigDecimal quantity, String note)
            throws InventoryException {
        User actor = access.require(sessionId);
        ProductInfo product = requireProduct(productId);
        BigDecimal qty = requirePositive(quantity, "Quantity to add");
        String cleanNote = cleanNote(note);
        Instant now = Instant.now();

        StockLevel after = repository.inTransaction(tx -> {
            StockLevel current = repository.lockOrCreate(tx, product.productId(), now);
            StockLevel next = current.withQuantity(current.quantityOnHand().add(qty), now);
            repository.update(tx, next);
            repository.addMovement(tx, movement(MovementType.STOCK_IN, current, next, null, null,
                    cleanNote, actor, now));
            return next;
        });
        log("STOCK_IN " + product.productId() + " +" + Quantity.format(qty), actor);
        return after;
    }

    /** Stock Out window: removes {@code quantity} for a reason from {@link StockReason#forStockOut()}. */
    public StockLevel stockOut(String sessionId, String productId, BigDecimal quantity,
                               StockReason reason, String note) throws InventoryException {
        User actor = access.require(sessionId);
        ProductInfo product = requireProduct(productId);
        BigDecimal qty = requirePositive(quantity, "Quantity to remove");
        requireReason(reason, StockReason.forStockOut(), note);
        String cleanNote = cleanNote(note);
        Instant now = Instant.now();

        StockLevel after = repository.inTransaction(tx -> {
            StockLevel current = repository.lockOrCreate(tx, product.productId(), now);
            if (current.quantityOnHand().compareTo(qty) < 0) {
                throw new InventoryException(shortMessage(product.name(), current.quantityOnHand(), qty));
            }
            StockLevel next = current.withQuantity(current.quantityOnHand().subtract(qty), now);
            repository.update(tx, next);
            repository.addMovement(tx, movement(MovementType.STOCK_OUT, current, next, reason, null,
                    cleanNote, actor, now));
            return next;
        });
        log("STOCK_OUT " + product.productId() + " -" + Quantity.format(qty) + " " + reason, actor);
        return after;
    }

    /**
     * Adjust Stock window: sets the stock to {@code newQuantity} (the counted amount), for a
     * reason from {@link StockReason#forAdjustment()}. The history records the difference.
     */
    public StockLevel adjust(String sessionId, String productId, BigDecimal newQuantity,
                             StockReason reason, String note) throws InventoryException {
        User actor = access.require(sessionId);
        ProductInfo product = requireProduct(productId);
        BigDecimal target = requireNotNegative(newQuantity, "New quantity");
        requireReason(reason, StockReason.forAdjustment(), note);
        String cleanNote = cleanNote(note);
        Instant now = Instant.now();

        StockLevel after = repository.inTransaction(tx -> {
            StockLevel current = repository.lockOrCreate(tx, product.productId(), now);
            if (current.quantityOnHand().compareTo(target) == 0) {
                throw new InventoryException("The new quantity is the same as the current stock ("
                        + Quantity.format(current.quantityOnHand()) + ").");
            }
            StockLevel next = current.withQuantity(target, now);
            repository.update(tx, next);
            repository.addMovement(tx, movement(MovementType.ADJUSTMENT, current, next, reason, null,
                    cleanNote, actor, now));
            return next;
        });
        log("STOCK_ADJUSTED " + product.productId() + " to " + Quantity.format(target) + " " + reason, actor);
        return after;
    }

    /** Sets the level at or below which a product shows as Low Stock. Not a stock movement. */
    public StockLevel setReorderLevel(String sessionId, String productId, BigDecimal reorderLevel)
            throws InventoryException {
        User actor = access.require(sessionId);
        ProductInfo product = requireProduct(productId);
        BigDecimal level = requireNotNegative(reorderLevel, "Reorder level");
        Instant now = Instant.now();

        StockLevel after = repository.inTransaction(tx -> {
            StockLevel current = repository.lockOrCreate(tx, product.productId(), now);
            StockLevel next = current.withReorderLevel(level, now);
            repository.update(tx, next);
            return next;
        });
        log("REORDER_LEVEL " + product.productId() + " " + Quantity.format(level), actor);
        return after;
    }

    // ---- called by POS inside the checkout transaction -----------------------

    /**
     * Deducts a completed sale from stock, joining POS's transaction ({@code tx}): never commits,
     * rolls back or closes it. If any product is short, throws and POS rolls back the whole sale,
     * so the stock-out happens once, together with the sale, or not at all.
     *
     * Lines of the same product are added up and written as ONE movement, and products are
     * locked in ID order so two terminals selling the same items cannot deadlock.
     */
    @Override
    public void recordSale(String reference, List<StockLine> lines, Connection tx)
            throws InsufficientStockException, SQLException {
        Objects.requireNonNull(reference, "reference");
        Map<String, BigDecimal> needed = new TreeMap<>();
        for (StockLine line : lines) {
            needed.merge(line.productId(), line.quantity(), BigDecimal::add);
        }
        Instant now = Instant.now();
        for (Map.Entry<String, BigDecimal> entry : needed.entrySet()) {
            String productId = entry.getKey();
            BigDecimal qty = Quantity.normalize(entry.getValue());
            StockLevel current = repository.lock(tx, productId).orElse(null);
            BigDecimal available = current == null ? BigDecimal.ZERO : current.quantityOnHand();
            if (current == null || available.compareTo(qty) < 0) {
                throw new InsufficientStockException(productId, nameOf(productId), available, qty);
            }
            StockLevel next = current.withQuantity(available.subtract(qty), now);
            repository.update(tx, next);
            repository.addMovement(tx, new StockMovement(0, productId, MovementType.SALE,
                    qty.negate(), available, next.quantityOnHand(), null, reference, null, null, now));
        }
    }

    // ---- helpers -------------------------------------------------------------

    private ProductInfo requireProduct(String productId) throws InventoryException {
        if (productId == null || productId.isBlank()) {
            throw new InventoryException("Select a product.");
        }
        return products.findById(productId.trim()).orElseThrow(
                () -> new InventoryException("Product " + productId.trim() + " no longer exists."));
    }

    private String nameOf(String productId) {
        return products.findById(productId).map(ProductInfo::name).orElse(productId);
    }

    private static BigDecimal requirePositive(BigDecimal value, String label) throws InventoryException {
        BigDecimal qty = checkAmount(value, label);
        if (qty.signum() <= 0) {
            throw new InventoryException(label + " must be more than zero.");
        }
        return qty;
    }

    private static BigDecimal requireNotNegative(BigDecimal value, String label) throws InventoryException {
        BigDecimal qty = checkAmount(value, label);
        if (qty.signum() < 0) {
            throw new InventoryException(label + " cannot be negative.");
        }
        return qty;
    }

    private static BigDecimal checkAmount(BigDecimal value, String label) throws InventoryException {
        if (value == null) {
            throw new InventoryException("Enter the " + label.toLowerCase() + ".");
        }
        if (value.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw new InventoryException(label + " can have at most " + MAX_DECIMALS + " decimal places.");
        }
        if (value.abs().compareTo(MAX_QUANTITY) > 0) {
            throw new InventoryException(label + " is too large.");
        }
        return Quantity.normalize(value);
    }

    private static void requireReason(StockReason reason, List<StockReason> allowed, String note)
            throws InventoryException {
        if (reason == null || !allowed.contains(reason)) {
            throw new InventoryException("Choose a reason.");
        }
        if (reason == StockReason.OTHER && (note == null || note.isBlank())) {
            throw new InventoryException("Add a note explaining the reason.");
        }
    }

    private static String cleanNote(String note) throws InventoryException {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new InventoryException("The note can be at most " + MAX_NOTE_LENGTH + " characters.");
        }
        return trimmed;
    }

    /** Same wording as InsufficientStockException, so the cashier and the stock clerk see one message. */
    private static String shortMessage(String name, BigDecimal available, BigDecimal requested) {
        return "Not enough stock for " + name + ": " + Quantity.format(available.max(BigDecimal.ZERO))
                + " left, " + Quantity.format(requested) + " needed.";
    }

    private static StockMovement movement(MovementType type, StockLevel before, StockLevel after,
                                          StockReason reason, String referenceNo, String note,
                                          User actor, Instant at) {
        return new StockMovement(0, before.productId(), type,
                after.quantityOnHand().subtract(before.quantityOnHand()),
                before.quantityOnHand(), after.quantityOnHand(),
                reason, referenceNo, note, actor.getUserId(), at);
    }

    private void log(String event, User actor) {
        if (auditLogger != null) {
            auditLogger.log(event, actor.getUserId());
        }
    }
}