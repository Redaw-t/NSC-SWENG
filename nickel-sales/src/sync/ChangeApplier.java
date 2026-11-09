package sync;

import shared.InvoiceCounters;
import shared.Json;
import shared.SyncTables;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes a change from another terminal (or the Main POS's list) into this PC's store tables, in the
 * caller's transaction. A change is the rows shared.SyncOutbox recorded; how each table is written
 * comes from {@link SyncTables}:
 *
 * - only-ever-added tables (sales, returns, stock movements, activity log, store details): the row is
 *   added if this PC doesn't have it yet, else skipped. So a change can arrive twice, or come back to
 *   the terminal that made it, and nothing is added twice;
 * - a NEW stock movement also changes this PC's stock by its quantity (stock is the sum of the
 *   movements; it may go below zero if two terminals sold the last item, see {@link #warnings});
 * - edited tables (products, categories, suppliers, users, reorder levels): the row is updated, or
 *   added if it is new. Every PC applies the Main POS's list in the same order, so the newest edit
 *   wins everywhere.
 *
 * Only tables and columns that are synced AND exist here are written: a change can't touch anything
 * else. A change that breaks a rule of this database (e.g. a barcode another product here already has)
 * throws {@link RejectedException}: it is skipped and logged, not retried forever.
 */
public final class ChangeApplier {

    /** A change this database can't take (a rule of the database refuses it); retrying won't help. */
    public static final class RejectedException extends Exception {
        public RejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();

    /** Applies {@code payloadJson} (one change) using {@code tx}; never commits, rolls back or closes it. */
    public void apply(Connection tx, String payloadJson) throws SQLException, RejectedException {
        Map<String, Object> payload;
        try {
            payload = Json.parseObject(payloadJson);
        } catch (IllegalArgumentException e) {
            throw new RejectedException("The change is not readable: " + e.getMessage(), e);
        }
        for (Object item : Json.list(payload, "rows")) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new RejectedException("The change has a row that is not an object.", null);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) raw;
            String table = Json.text(row, "t");
            SyncTables.Spec spec = SyncTables.of(table)
                    .orElseThrow(() -> new RejectedException("The table \"" + table + "\" is not synced.", null));
            Map<String, String> values = values(tx, spec, Json.object(row, "c"));
            try {
                if (spec.op() == SyncTables.Op.INSERT) {
                    insertIfNew(tx, spec, values);
                } else {
                    upsert(tx, spec, values);
                }
            } catch (SQLException e) {
                if (isRuleBroken(e)) {
                    throw new RejectedException(spec.table() + " " + describeKey(spec, values) + ": "
                            + e.getMessage(), e);
                }
                throw e;
            }
        }
    }

    /**
     * Things worth an admin's attention from the changes applied since the last call (e.g. stock that
     * went below zero), then forgets them.
     */
    public List<String> warnings() {
        List<String> out = List.copyOf(warnings);
        warnings.clear();
        return out;
    }

    /* ---------------------------------- writing ---------------------------------- */

    private void insertIfNew(Connection tx, SyncTables.Spec spec, Map<String, String> values) throws SQLException {
        if (spec.table().equals("Sale")) {
            // Every PC keeps where each register's invoice numbers have got to (shared.InvoiceCounters),
            // so a register set up again from a copy continues after its last number.
            InvoiceCounters.atLeastPast(tx, values.get("InvoiceNo"));
        }
        if (exists(tx, spec, values)) {
            return;   // already here: sent twice, or this PC's own change coming back
        }
        insert(tx, spec.table(), values);
        if (spec.table().equals("StockMovement")) {
            addToStock(tx, values.get("ProductID"), values.get("Quantity"), values.get("ReferenceNo"));
        }
    }

    private void upsert(Connection tx, SyncTables.Spec spec, Map<String, String> values) throws SQLException {
        if (!exists(tx, spec, values)) {
            Map<String, String> row = new LinkedHashMap<>(values);
            if (spec.table().equals("Inventory")) {
                // A reorder level for a product this PC has never stocked: an empty stock row.
                row.put("QuantityOnHand", "0");
                row.put("UpdatedAt", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                        .format(SyncDb.utc(Instant.now())));
            }
            insert(tx, spec.table(), row);
            return;
        }
        List<String> set = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!spec.key().contains(e.getKey())) {
                set.add(quote(e.getKey()) + " = ?");
                args.add(e.getValue());
            }
        }
        if (set.isEmpty()) {
            return;
        }
        String sql = "UPDATE " + quote(spec.table()) + " SET " + String.join(", ", set) + " WHERE " + keyWhere(spec);
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            int i = 1;
            for (String value : args) {
                bind(ps, i++, value);
            }
            for (String k : spec.key()) {
                bind(ps, i++, values.get(k));
            }
            ps.executeUpdate();
        }
    }

    private static void insert(Connection tx, String table, Map<String, String> values) throws SQLException {
        List<String> names = new ArrayList<>();
        for (String name : values.keySet()) {
            names.add(quote(name));
        }
        String sql = "INSERT INTO " + quote(table) + " (" + String.join(", ", names) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(names.size(), "?")) + ")";
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            int i = 1;
            for (String value : values.values()) {
                bind(ps, i++, value);
            }
            ps.executeUpdate();
        }
    }

    /** A new stock movement from another terminal: this PC's stock changes by the same amount. */
    private void addToStock(Connection tx, String productId, String quantity, String reference) throws SQLException {
        if (productId == null || quantity == null) {
            return;
        }
        BigDecimal change = new BigDecimal(quantity);
        Object now = SyncDb.utc(Instant.now());
        try (PreparedStatement ps = tx.prepareStatement(
                "UPDATE Inventory SET QuantityOnHand = QuantityOnHand + ?, UpdatedAt = ? WHERE ProductID = ?")) {
            ps.setBigDecimal(1, change);
            ps.setObject(2, now);
            ps.setString(3, productId);
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement add = tx.prepareStatement("INSERT INTO Inventory (ProductID, QuantityOnHand, "
                        + "ReorderLevel, UpdatedAt) VALUES (?, ?, 0, ?)")) {
                    add.setString(1, productId);
                    add.setBigDecimal(2, change);
                    add.setObject(3, now);
                    add.executeUpdate();
                }
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT QuantityOnHand FROM Inventory WHERE ProductID = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getBigDecimal(1).signum() < 0 && change.signum() < 0) {
                    warnings.add("Stock of " + productId + " is now " + shared.Quantity.format(rs.getBigDecimal(1))
                            + " after " + (reference == null ? "a sale on another terminal" : reference)
                            + ": two terminals sold the last items while they couldn't reach each other. "
                            + "Count it and adjust the stock.");
                }
            }
        }
    }

    /* ---------------------------------- checking ---------------------------------- */

    /** The row's values: only columns that are synced and exist here; the key must be there. */
    private Map<String, String> values(Connection tx, SyncTables.Spec spec, Map<String, Object> columns)
            throws SQLException, RejectedException {
        Set<String> known = columns(tx, spec.table());
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : columns.entrySet()) {
            String name = e.getKey();
            String match = known.stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
            if (match == null || spec.localOnly().contains(match)) {
                continue;   // a column this PC doesn't have (an older database) or keeps for itself
            }
            out.put(match, e.getValue() == null ? null : Json.text(columns, name));
        }
        for (String k : spec.key()) {
            if (out.get(k) == null) {
                throw new RejectedException("A " + spec.table() + " row came without its " + k + ".", null);
            }
        }
        return out;
    }

    /** The table's columns here (asked once per table). */
    private Set<String> columns(Connection tx, String table) throws SQLException {
        Set<String> cached = columnsByTable.get(table);
        if (cached != null) {
            return cached;
        }
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (PreparedStatement ps = tx.prepareStatement("SELECT * FROM " + quote(table) + " WHERE 1 = 0");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnName(i));
            }
        }
        columnsByTable.put(table, names);
        return names;
    }

    private static boolean exists(Connection tx, SyncTables.Spec spec, Map<String, String> values) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT 1 FROM " + quote(spec.table()) + " WHERE " + keyWhere(spec) + " FOR UPDATE")) {
            int i = 1;
            for (String k : spec.key()) {
                bind(ps, i++, values.get(k));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String keyWhere(SyncTables.Spec spec) {
        List<String> parts = new ArrayList<>();
        for (String k : spec.key()) {
            parts.add(quote(k) + " = ?");
        }
        return String.join(" AND ", parts);
    }

    private static String describeKey(SyncTables.Spec spec, Map<String, String> values) {
        List<String> parts = new ArrayList<>();
        for (String k : spec.key()) {
            parts.add(values.get(k));
        }
        return String.join("/", parts);
    }

    /** A database rule refused it (duplicate value, missing parent row, value too long ...): SQLState 22 or 23. */
    static boolean isRuleBroken(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("23") || state.startsWith("22"));
    }

    private static void bind(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** A table or column name in backticks (names come from SyncTables or this database, never from the sender). */
    private static String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
    }
}
