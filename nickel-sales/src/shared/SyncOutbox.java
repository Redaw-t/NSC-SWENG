package shared;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sync queue: every change to a synced table (see {@link SyncTables}) also writes one row to
 * nickelsales_appconfig.SyncOutbox, IN THE SAME TRANSACTION, so a change and its queue row are saved
 * together or not at all. The sync worker (sync.SyncEngine) sends the queue to the Main POS.
 *
 * A change is one or more whole rows, read back from the database right after they were written:
 * <pre>
 *   {"rows":[{"t":"Sale","c":{"SaleID":"...","Total":"150.00",...}}, {"t":"SaleLine","c":{...}}]}
 * </pre>
 * Every value is sent as text (or null), exactly as MySQL shows it; the other terminal writes it
 * back the same way. Columns a table keeps per PC ({@link SyncTables.Spec#localOnly()}) are left out.
 *
 * Off until the app turns it on ({@link #enable}): the self-tests write to the store tables
 * without filling a queue nobody sends. If the queue table is missing (a database made by an older
 * script), the change is saved without it and a warning is printed once.
 */
public final class SyncOutbox {

    private static final int NO_SUCH_TABLE = 1146;

    private static volatile boolean enabled;
    private static volatile boolean warnedMissing;
    private static volatile Runnable onRecorded = () -> { };

    private SyncOutbox() { }

    /** Turns recording on (the app, at start-up) or off. */
    public static void enable(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * Called after every change is queued (the sync worker sends it at once instead of waiting for its
     * next round); null for nothing. It runs inside the change's transaction, so it must only signal.
     */
    public static void onRecorded(Runnable listener) {
        onRecorded = listener == null ? () -> { } : listener;
    }

    /** A new change or row ID, unique across all terminals. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Queues a change, using the caller's connection {@code c} (its transaction, if one is open;
     * never commits, rolls back or closes it).
     *
     * @param kind     what it is about, e.g. {@link SyncTables#SALE}
     * @param recordId what it is, as people know it: an invoice number, product ID ...
     * @param rows     the rows, from {@link #rows} / {@link #row}
     */
    public static void record(Connection c, String kind, String recordId, List<Map<String, Object>> rows)
            throws SQLException {
        if (!enabled) {
            return;
        }
        record(c, AppConfigDatabase.NAME, kind, recordId, rows);
    }

    /**
     * {@link #record} into the queue of the app-configuration database {@code appConfigSchema},
     * whether or not recording is on (for sync's own tests, which run two terminals' databases).
     */
    public static void record(Connection c, String appConfigSchema, String kind, String recordId,
                              List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", rows);
        String sql = "INSERT INTO `" + appConfigSchema + "`.SyncOutbox (ChangeID, Kind, RecordID, Payload, "
                + "CreatedAt) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newId());
            ps.setString(2, kind);
            ps.setString(3, recordId == null ? "" : recordId.length() > 50 ? recordId.substring(0, 50) : recordId);
            ps.setString(4, Json.write(payload));
            ps.setObject(5, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            ps.executeUpdate();
            onRecorded.run();
        } catch (SQLException e) {
            if (e.getErrorCode() == NO_SUCH_TABLE) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    System.err.println("[SYNC] The sync queue table is missing (run setup to update the database); "
                            + "changes are saved but not sent to the other terminals.");
                }
                return;
            }
            throw e;
        }
    }

    /** {@link #record} of the rows of one table matching {@code where}. */
    public static void recordRows(Connection c, String kind, String recordId, String table, String where,
                                  Object... args) throws SQLException {
        if (enabled) {
            record(c, kind, recordId, rows(c, table, where, args));
        }
    }

    /**
     * The rows of {@code table} matching {@code where} (e.g. "SaleID = ?"), ready for {@link #record}.
     *
     * @throws IllegalArgumentException the table is not synced
     */
    public static List<Map<String, Object>> rows(Connection c, String table, String where, Object... args)
            throws SQLException {
        SyncTables.Spec spec = SyncTables.of(table)
                .orElseThrow(() -> new IllegalArgumentException(table + " is not a synced table"));
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM `" + spec.table() + "` WHERE " + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> columns = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String name = meta.getColumnLabel(i);
                        if (spec.localOnly().contains(name)) {
                            continue;
                        }
                        columns.put(name, text(rs, i, meta.getColumnType(i)));
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("t", spec.table());
                    row.put("c", columns);
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** A column's value as the text MySQL takes back: booleans as 1 / 0, null as null. */
    private static String text(ResultSet rs, int column, int type) throws SQLException {
        if (type == Types.BIT || type == Types.BOOLEAN) {
            boolean value = rs.getBoolean(column);
            return rs.wasNull() ? null : value ? "1" : "0";
        }
        return rs.getString(column);
    }
}
