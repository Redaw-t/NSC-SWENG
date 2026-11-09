package sync;

import shared.AppConfigDatabase;
import shared.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The sync tables in nickelsales_appconfig (SyncOutbox, SyncFeed, SyncFailed, IdCounter, and the
 * SyncAppliedSeq setting), reached through a connection to the store database (NickelSales_Database)
 * so one transaction can change both: e.g. apply a change to the store tables and move this PC's
 * place in the Main POS's list, together or not at all.
 *
 * The connection and the schema name are given, so the self-test can run two terminals' databases
 * side by side; the app uses {@link #standard()}.
 */
public final class SyncDb {

    /** Opens a connection to a store database. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    /** A change waiting in this PC's queue. */
    public record Change(String changeId, String kind, String recordId, String payload, Instant createdAt) { }

    /** A change in the Main POS's list, with its place {@code seq}. */
    public record FeedEntry(long seq, String changeId, String source, String kind, String recordId, String payload,
                            Instant createdAt) { }

    /** Work in one transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection tx) throws SQLException;
    }

    static final String APPLIED_SEQ = "SyncAppliedSeq";

    private final Connections store;
    private final String cfg;   // the app-configuration schema, e.g. nickelsales_appconfig

    public SyncDb(Connections store, String appConfigSchema) {
        this.store = store;
        this.cfg = "`" + appConfigSchema + "`";
    }

    /** The app's own databases. */
    public static SyncDb standard() {
        return new SyncDb(Database::getConnection, AppConfigDatabase.NAME);
    }

    /** Runs {@code work} as one transaction on the store database. */
    public <T> T inTransaction(Work<T> work) throws SQLException {
        try (Connection tx = store.open()) {
            tx.setAutoCommit(false);
            try {
                T result = work.run(tx);
                tx.commit();
                return result;
            } catch (Throwable t) {
                try {
                    tx.rollback();
                } catch (SQLException rollbackFailure) {
                    t.addSuppressed(rollbackFailure);
                }
                throw t;
            }
        }
    }

    /* ------------------------------ this PC's queue ------------------------------ */

    /** Changes not sent yet, oldest first, at most {@code limit}. */
    public List<Change> unsent(int limit) throws SQLException {
        try (Connection c = store.open();
             PreparedStatement ps = c.prepareStatement("SELECT ChangeID, Kind, RecordID, Payload, CreatedAt FROM "
                     + cfg + ".SyncOutbox WHERE SentAt IS NULL ORDER BY OutboxID LIMIT ?")) {
            ps.setInt(1, limit);
            return changes(ps);
        }
    }

    /** How many changes are waiting to be sent. */
    public int pendingCount() throws SQLException {
        try (Connection c = store.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + cfg + ".SyncOutbox WHERE SentAt IS NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * The Main POS has these changes: marks them sent, and the sales and returns among them as synced
     * (Sale.SyncedAt / Return.SyncedAt, as the status screens count them).
     */
    public void markSent(List<Change> changes, Instant at) throws SQLException {
        if (changes.isEmpty()) {
            return;
        }
        inTransaction(tx -> {
            markSent(tx, changes, at);
            return null;
        });
    }

    private void markSent(Connection tx, List<Change> changes, Instant at) throws SQLException {
        try (PreparedStatement sent = tx.prepareStatement(
                     "UPDATE " + cfg + ".SyncOutbox SET SentAt = ? WHERE ChangeID = ? AND SentAt IS NULL");
             PreparedStatement sale = tx.prepareStatement(
                     "UPDATE Sale SET SyncedAt = ? WHERE InvoiceNo = ? AND SyncedAt IS NULL");
             PreparedStatement ret = tx.prepareStatement(
                     "UPDATE `Return` SET SyncedAt = ? WHERE ReturnNo = ? AND SyncedAt IS NULL")) {
            for (Change ch : changes) {
                sent.setObject(1, utc(at));
                sent.setString(2, ch.changeId());
                sent.addBatch();
                PreparedStatement mark = switch (ch.kind()) {
                    case shared.SyncTables.SALE -> sale;
                    case shared.SyncTables.RETURN -> ret;
                    default -> null;
                };
                if (mark != null) {
                    mark.setObject(1, utc(at));
                    mark.setString(2, ch.recordId());
                    mark.addBatch();
                }
            }
            sent.executeBatch();
            sale.executeBatch();
            ret.executeBatch();
        }
    }

    /**
     * Empties the queue: a new Additional POS has just been given the store's data, so anything this
     * computer did before (e.g. trying the app out) must never reach the store.
     */
    public void clearOutbox(Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM " + cfg + ".SyncOutbox")) {
            ps.executeUpdate();
        }
    }

    /* --------------------------- the Main POS's list ---------------------------- */

    /**
     * Main POS: adds changes from {@code source} to the list (one transaction); a change it already
     * has is skipped, so sending the same change twice is harmless.
     *
     * @return how many were taken (one already there is counted too)
     */
    public int accept(String source, List<Change> changes, Instant at) throws SQLException {
        if (changes.isEmpty()) {
            return 0;
        }
        return inTransaction(tx -> accept(tx, source, changes, at));
    }

    private int accept(Connection tx, String source, List<Change> changes, Instant at) throws SQLException {
        int added = 0;
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO " + cfg + ".SyncFeed (ChangeID, SourceTerminal, "
                + "Kind, RecordID, Payload, CreatedAt, AcceptedAt) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE ChangeID = ChangeID")) {
            for (Change ch : changes) {
                ps.setString(1, ch.changeId());
                ps.setString(2, fit(source, 10));
                ps.setString(3, fit(ch.kind(), 20));
                ps.setString(4, fit(ch.recordId(), 50));
                ps.setString(5, ch.payload());
                ps.setObject(6, utc(ch.createdAt()));
                ps.setObject(7, utc(at));
                added += ps.executeUpdate() > 0 ? 1 : 0;
            }
        }
        return added;
    }

    /**
     * Main POS: moves its own queued changes into the list, oldest first (one transaction).
     *
     * @return the changes moved
     */
    public List<Change> drainOwnOutbox(String terminalId, int limit, Instant at) throws SQLException {
        return inTransaction(tx -> {
            List<Change> mine;
            try (PreparedStatement ps = tx.prepareStatement("SELECT ChangeID, Kind, RecordID, Payload, CreatedAt FROM "
                    + cfg + ".SyncOutbox WHERE SentAt IS NULL ORDER BY OutboxID LIMIT ? FOR UPDATE")) {
                ps.setInt(1, limit);
                mine = changes(ps);
            }
            accept(tx, terminalId, mine, at);
            markSent(tx, mine, at);
            return mine;
        });
    }

    /** Main POS: the list after place {@code seq}, in order, at most {@code limit}. */
    public List<FeedEntry> feedAfter(long seq, int limit) throws SQLException {
        try (Connection c = store.open();
             PreparedStatement ps = c.prepareStatement("SELECT Seq, ChangeID, SourceTerminal, Kind, RecordID, Payload, "
                     + "CreatedAt FROM " + cfg + ".SyncFeed WHERE Seq > ? ORDER BY Seq LIMIT ?")) {
            ps.setLong(1, seq);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<FeedEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new FeedEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), instant(rs.getObject(7, LocalDateTime.class))));
                }
                return out;
            }
        }
    }

    /** Main POS: the last place in its list (0 if it is empty). */
    public long feedHead() throws SQLException {
        try (Connection c = store.open();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(Seq), 0) FROM " + cfg + ".SyncFeed");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /* ------------------------- this PC's place in the list ------------------------- */

    /** The last change in the Main POS's list applied on this PC (0 = none yet). */
    public long appliedSeq() throws SQLException {
        try (Connection c = store.open()) {
            return appliedSeq(c);
        }
    }

    private long appliedSeq(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT SettingValue FROM " + cfg + ".AppSettings WHERE SettingKey = ?")) {
            ps.setString(1, APPLIED_SEQ);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null && !rs.getString(1).isBlank()) {
                    return Long.parseLong(rs.getString(1).trim());
                }
                return 0;
            }
        }
    }

    /** Sets this PC's place, inside the caller's transaction (with the change it applied). */
    public void setAppliedSeq(Connection tx, long seq) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO " + cfg + ".AppSettings (SettingKey, SettingValue) "
                + "VALUES (?, ?) ON DUPLICATE KEY UPDATE SettingValue = VALUES(SettingValue)")) {
            ps.setString(1, APPLIED_SEQ);
            ps.setString(2, Long.toString(seq));
            ps.executeUpdate();
        }
    }

    /* ------------------------- changes that could not be applied ------------------------- */

    public void saveFailed(long syncLogId, FeedEntry e, Instant at) throws SQLException {
        try (Connection c = store.open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + cfg + ".SyncFailed (SyncLogID, Seq, ChangeID, "
                     + "SourceTerminal, Kind, RecordID, Payload, FailedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                     + "ON DUPLICATE KEY UPDATE FailedAt = VALUES(FailedAt)")) {
            ps.setLong(1, syncLogId);
            ps.setLong(2, e.seq());
            ps.setString(3, e.changeId());
            ps.setString(4, fit(e.source(), 10));
            ps.setString(5, fit(e.kind(), 20));
            ps.setString(6, fit(e.recordId(), 50));
            ps.setString(7, e.payload());
            ps.setObject(8, utc(at));
            ps.executeUpdate();
        }
    }

    public Optional<FeedEntry> failed(long syncLogId) throws SQLException {
        try (Connection c = store.open();
             PreparedStatement ps = c.prepareStatement("SELECT Seq, ChangeID, SourceTerminal, Kind, RecordID, Payload, "
                     + "FailedAt FROM " + cfg + ".SyncFailed WHERE SyncLogID = ?")) {
            ps.setLong(1, syncLogId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new FeedEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), instant(rs.getObject(7, LocalDateTime.class))))
                        : Optional.empty();
            }
        }
    }

    public void deleteFailed(long syncLogId) throws SQLException {
        try (Connection c = store.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + cfg + ".SyncFailed WHERE SyncLogID = ?")) {
            ps.setLong(1, syncLogId);
            ps.executeUpdate();
        }
    }

    /* ------------------------------- housekeeping ------------------------------- */

    /** Deletes sent queue rows older than {@code sentBefore} and list rows older than {@code feedBefore}. */
    public void purge(Instant sentBefore, Instant feedBefore) throws SQLException {
        try (Connection c = store.open();
             PreparedStatement outbox = c.prepareStatement(
                     "DELETE FROM " + cfg + ".SyncOutbox WHERE SentAt IS NOT NULL AND SentAt < ?");
             PreparedStatement feed = c.prepareStatement("DELETE FROM " + cfg + ".SyncFeed WHERE AcceptedAt < ?")) {
            outbox.setObject(1, utc(sentBefore));
            outbox.executeUpdate();
            feed.setObject(1, utc(feedBefore));
            feed.executeUpdate();
        }
    }

    /* ------------------------------ Main POS: new IDs ------------------------------ */

    /**
     * Main POS: the next ID of {@code kind} (shared.MasterIds), never handed out twice: one more
     * than both the highest in use and the highest handed out before (to this or another terminal).
     */
    public String allocateId(String kind) throws SQLException {
        String max = switch (kind) {
            case shared.MasterIds.PRODUCT -> "SELECT COALESCE(MAX(CAST(SUBSTRING(ProductID, 3) AS UNSIGNED)), 0) "
                    + "FROM Product WHERE ProductID REGEXP '^P-[0-9]+$'";
            case shared.MasterIds.USER -> "SELECT COALESCE(MAX(CAST(SUBSTRING(UserID, 5) AS UNSIGNED)), 0) "
                    + "FROM Users WHERE UserID REGEXP '^NSC-[0-9]+$'";
            case shared.MasterIds.CATEGORY -> "SELECT COALESCE(MAX(CategoryID), 0) FROM Category";
            case shared.MasterIds.SUPPLIER -> "SELECT COALESCE(MAX(SupplierID), 0) FROM Supplier";
            default -> throw new IllegalArgumentException("No IDs of kind " + kind);
        };
        long next = inTransaction(tx -> {
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO " + cfg + ".IdCounter (Kind, NextValue) "
                    + "VALUES (?, 1) ON DUPLICATE KEY UPDATE Kind = Kind")) {
                ps.setString(1, kind);
                ps.executeUpdate();
            }
            long counter;
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT NextValue FROM " + cfg + ".IdCounter WHERE Kind = ? FOR UPDATE")) {
                ps.setString(1, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    counter = rs.getLong(1);
                }
            }
            long inUse;
            try (PreparedStatement ps = tx.prepareStatement(max); ResultSet rs = ps.executeQuery()) {
                rs.next();
                inUse = rs.getLong(1);
            }
            long id = Math.max(counter, inUse + 1);
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE " + cfg + ".IdCounter SET NextValue = ? WHERE Kind = ?")) {
                ps.setLong(1, id + 1);
                ps.setString(2, kind);
                ps.executeUpdate();
            }
            return id;
        });
        return switch (kind) {
            case shared.MasterIds.PRODUCT -> String.format("P-%04d", next);
            case shared.MasterIds.USER -> String.format("NSC-%05d", next);
            default -> Long.toString(next);
        };
    }

    /* --------------------------------- helpers --------------------------------- */

    private static List<Change> changes(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Change> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Change(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        instant(rs.getObject(5, LocalDateTime.class))));
            }
            return out;
        }
    }

    static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private static String fit(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max);
    }
}
