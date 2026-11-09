package shared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which store tables are synced between the POS terminals, and how. The one list both sides use:
 * {@link SyncOutbox} (what a change records) and sync.ChangeApplier (what another terminal may write).
 * A table that is not here is never synced (InvoiceSequence, BackupRecord, SyncLog: each PC's own).
 *
 * Two kinds of table:
 * - {@link Op#INSERT}: rows are only ever added, never changed (sales, returns, stock movements,
 *   activity log, store details). A row is copied once; a row already there is skipped. Their IDs
 *   never clash: sales and returns use UUIDs, the others a GlobalID (UUID) column, and their own
 *   AUTO_INCREMENT number stays on each PC (it is in {@code localOnly}).
 * - {@link Op#UPSERT}: rows that are edited (products, categories, suppliers, users, reorder levels).
 *   The whole row is copied; the newest change in the Main POS's order wins. New IDs come from the
 *   Main POS ({@link MasterIds}), so two terminals never make the same one.
 */
public final class SyncTables {

    /** How a synced row is written on the other terminals. */
    public enum Op { INSERT, UPSERT }

    /**
     * @param key       the columns that identify the row on every terminal
     * @param localOnly columns that stay on each PC and are never sent
     */
    public record Spec(String table, Op op, List<String> key, Set<String> localOnly) { }

    /* What one change is about (SyncOutbox.Kind column; shown in the Sync log). */
    public static final String SALE = "SALE";
    public static final String RETURN = "RETURN";
    public static final String STOCK = "STOCK";
    public static final String ACTIVITY = "ACTIVITY";
    public static final String STORE = "STORE";
    public static final String CATEGORY = "CATEGORY";
    public static final String SUPPLIER = "SUPPLIER";
    public static final String PRODUCT = "PRODUCT";
    public static final String USER = "USER";
    public static final String REORDER = "REORDER";

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        add("Sale", Op.INSERT, List.of("SaleID"), Set.of("SyncedAt"));
        add("SaleLine", Op.INSERT, List.of("SaleID", "LineNo"), Set.of());
        add("Payment", Op.INSERT, List.of("SaleID", "PaymentNo"), Set.of());
        add("Return", Op.INSERT, List.of("ReturnID"), Set.of("SyncedAt"));
        add("ReturnLine", Op.INSERT, List.of("ReturnID", "LineNo"), Set.of());
        add("StockMovement", Op.INSERT, List.of("GlobalID"), Set.of("MovementID"));
        add("ActivityLog", Op.INSERT, List.of("GlobalID"), Set.of("LogID"));
        add("StoreDetails", Op.INSERT, List.of("GlobalID"), Set.of("StoreDetailsID"));
        add("Category", Op.UPSERT, List.of("CategoryID"), Set.of());
        add("Supplier", Op.UPSERT, List.of("SupplierID"), Set.of());
        add("Product", Op.UPSERT, List.of("ProductID"), Set.of());
        add("Users", Op.UPSERT, List.of("UserID"), Set.of("LastSignIn"));
        // Only the reorder level: stock itself follows from the stock movements on each PC.
        add("Inventory", Op.UPSERT, List.of("ProductID"), Set.of("QuantityOnHand", "UpdatedAt"));
    }

    private SyncTables() { }

    private static void add(String table, Op op, List<String> key, Set<String> localOnly) {
        SPECS.put(table.toLowerCase(java.util.Locale.ROOT), new Spec(table, op, key, localOnly));
    }

    /** How {@code table} is synced; empty if it is not synced at all. Upper/lower case is ignored. */
    public static Optional<Spec> of(String table) {
        return table == null ? Optional.empty()
                : Optional.ofNullable(SPECS.get(table.toLowerCase(java.util.Locale.ROOT)));
    }

    /** Every synced table, in the order a fresh database needs them (parents first). */
    public static List<Spec> all() {
        return List.copyOf(SPECS.values());
    }
}
