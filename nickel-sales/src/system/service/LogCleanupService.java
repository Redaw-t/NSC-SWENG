package system.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import shared.AppConfigDatabase;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Clear Logs (Settings &gt; General, Admin only): deletes old entries of the logs that are only a
 * record of what happened, never data the system works from:
 * - the activity log (ActivityLog);
 * - the sync log (SyncLog), except a failed change still waiting for its Retry;
 * - the backup history (BackupRecord), except the newest successful backup (System Status's Last
 *   Backup); the backup FILES are never touched.
 *
 * Before anything is deleted, the entries that will go are saved as CSV files (one per log) in the
 * backup folder, so nothing is lost by mistake: the admin keeps them on another drive and deletes them
 * from the PC. If a file can't be saved, nothing is deleted.
 *
 * Never touched: sales, returns, stock and its movement history, products, users, store details and
 * their log, settings. Clearing is on THIS register only (each keeps its own logs; deleting is not
 * synced), and is itself written to the activity log (who cleared what), after the clearing, so it
 * is never cleared with it.
 */
public final class LogCleanupService {

    /** A log that may be cleared. */
    public enum Log {
        ACTIVITY("Activity log", "ActivityLog", "LogID", "CreatedAt",
                "Sign-ins, changes and sales, as listed in Audit Logs."),
        SYNC("Sync log", "SyncLog", "LogID", "CreatedAt",
                "Each send and receive between registers. A failed change still waiting for Retry is kept."),
        BACKUP_HISTORY("Backup history", "BackupRecord", "BackupID", "CreatedAt",
                "The list in Backup & Restore. The backup files themselves are kept, and so is the newest backup.");

        private final String label;
        private final String table;
        private final String idColumn;
        private final String timeColumn;
        private final String about;

        Log(String label, String table, String idColumn, String timeColumn, String about) {
            this.label = label;
            this.table = table;
            this.idColumn = idColumn;
            this.timeColumn = timeColumn;
            this.about = about;
        }

        public String label() {
            return label;
        }

        public String about() {
            return about;
        }
    }

    /** Which entries: everything, or older than some days / months. */
    public enum Period {
        OLDER_30_DAYS("Older than 30 days", 30, 0),
        OLDER_60_DAYS("Older than 60 days", 60, 0),
        OLDER_3_MONTHS("Older than 3 months", 0, 3),
        OLDER_6_MONTHS("Older than 6 months", 0, 6),
        OLDER_1_YEAR("Older than 1 year", 0, 12),
        ALL_TIME("All time", 0, 0);

        private final String label;
        private final int days;
        private final int months;

        Period(String label, int days, int months) {
            this.label = label;
            this.days = days;
            this.months = months;
        }

        public String label() {
            return label;
        }

        /** Entries before this are cleared; null = all of them. */
        Instant cutoff(Instant now) {
            ZonedDateTime ph = shared.PhTime.of(now);
            if (days > 0) {
                return ph.minusDays(days).toInstant();
            }
            if (months > 0) {
                return ph.minusMonths(months).toInstant();
            }
            return null;
        }
    }

    /** What a clearing did: how many entries of each log went, and the CSV copies saved first. */
    public record Result(Map<Log, Long> deleted, List<Path> copies) {
        public long total() {
            return deleted.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** Opens a connection to the store database. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US);

    private final Connections database;
    private final SystemAccess access;
    private final AuditLogger audit;   // may be null
    private final Clock clock;
    private final Supplier<Path> copyFolder;
    private final String appConfigSchema;

    /** @param copyFolder where the CSV copies of the cleared entries go (the backup folder) */
    public LogCleanupService(Connections database, SystemAccess access, AuditLogger audit, Clock clock,
                             Supplier<Path> copyFolder) {
        this(database, access, audit, clock, copyFolder, AppConfigDatabase.NAME);
    }

    /** As above, with the app-configuration database's name (the self-test uses scratch databases). */
    public LogCleanupService(Connections database, SystemAccess access, AuditLogger audit, Clock clock,
                             Supplier<Path> copyFolder, String appConfigSchema) {
        this.database = Objects.requireNonNull(database, "database");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = audit;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.copyFolder = Objects.requireNonNull(copyFolder, "copyFolder");
        this.appConfigSchema = Objects.requireNonNull(appConfigSchema, "appConfigSchema");
    }

    /** The folder the CSV copies go to. */
    public Path copyFolder() {
        return copyFolder.get();
    }

    /** How many entries of each log {@link #clear} would delete now. */
    public Map<Log, Long> count(String sessionId, Set<Log> logs, Period period) throws SystemException {
        access.require(sessionId);
        Map<Log, Long> out = new EnumMap<>(Log.class);
        try (Connection c = database.open()) {
            Instant cutoff = period.cutoff(clock.instant());
            for (Log log : logs) {
                try (PreparedStatement ps = statement(c, "SELECT COUNT(*) FROM ", log, cutoff);
                     ResultSet rs = ps.executeQuery()) {
                    out.put(log, rs.next() ? rs.getLong(1) : 0);
                }
            }
        } catch (SQLException e) {
            throw new SystemException("The logs could not be counted. Check that MySQL is running, then try again.", e);
        }
        return out;
    }

    /**
     * Saves the entries that will go as CSV files in {@link #copyFolder()}, then deletes them, all in one
     * transaction (if a file can't be saved, nothing is deleted); then writes who did it to the activity log.
     */
    public Result clear(String sessionId, Set<Log> logs, Period period) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(period, "period");
        if (logs == null || logs.isEmpty()) {
            throw new SystemException("Choose at least one log to clear.");
        }
        Instant now = clock.instant();
        Instant cutoff = period.cutoff(now);
        Path folder = copyFolder.get();
        Map<Log, Long> deleted = new EnumMap<>(Log.class);
        List<Path> copies = new ArrayList<>();
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                for (Log log : logs) {
                    Path copy = saveCopy(c, log, cutoff, folder, now);   // first: the entries, as CSV
                    if (copy != null) {
                        copies.add(copy);
                    }
                    try (PreparedStatement ps = statement(c, "DELETE FROM ", log, cutoff)) {
                        deleted.put(log, (long) ps.executeUpdate());
                    }
                }
                c.commit();
            } catch (SQLException | SystemException | RuntimeException e) {
                c.rollback();
                deleteQuietly(copies);   // nothing was deleted, so the copies aren't needed
                throw e;
            }
        } catch (SQLException e) {
            throw new SystemException("The logs could not be cleared, and nothing was deleted. Check that MySQL "
                    + "is running, then try again.", e);
        }
        Result result = new Result(deleted, copies);
        if (audit != null) {
            String which = deleted.keySet().stream().map(l -> l.table).collect(Collectors.joining(","));
            audit.log("LOGS_CLEARED target=" + which + " " + result.total() + " entries, "
                    + period.label().toLowerCase(Locale.ROOT) + ", copy in " + folder, actor.getUserId());
        }
        return result;
    }

    /**
     * Writes the entries of {@code log} that will be cleared to a CSV file (every column; times as stored,
     * in UTC). Null if there are none.
     */
    private Path saveCopy(Connection c, Log log, Instant cutoff, Path folder, Instant now)
            throws SQLException, SystemException {
        List<String> header = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        try (PreparedStatement ps = statement(c, "SELECT * FROM ", log, cutoff, " ORDER BY " + log.idColumn);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String name = meta.getColumnLabel(i);
                header.add(name.equals(log.timeColumn) ? name + " (UTC)" : name);
            }
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        try {
            java.nio.file.Files.createDirectories(folder);
        } catch (java.io.IOException e) {
            throw new SystemException("The copy of the logs could not be saved in " + folder + ", so nothing was "
                    + "deleted. Check the backup folder in Backup & Restore.", e);
        }
        Path file = folder.resolve("nickelsales-cleared-" + log.table + "-" + FILE_TIME.format(shared.PhTime.of(now))
                + ".csv");
        try {
            Csv.write(file, header, rows);
        } catch (SystemException e) {
            throw new SystemException("The copy of the logs could not be saved in " + folder + ", so nothing was "
                    + "deleted. Check the backup folder in Backup & Restore.", e);
        }
        return file;
    }

    /** "SELECT ... / DELETE FROM" {@code log}'s entries before {@code cutoff} (all if null), keeping what must stay. */
    private PreparedStatement statement(Connection c, String verb, Log log, Instant cutoff) throws SQLException {
        return statement(c, verb, log, cutoff, "");
    }

    private PreparedStatement statement(Connection c, String verb, Log log, Instant cutoff, String tail)
            throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (cutoff != null) {
            where.append(" AND ").append(log.timeColumn).append(" < ?");
        }
        switch (log) {
            case SYNC -> {
                if (hasTable(c, appConfigSchema, "SyncFailed")) {   // a failed change still waiting for its Retry
                    where.append(" AND LogID NOT IN (SELECT SyncLogID FROM `").append(appConfigSchema)
                            .append("`.SyncFailed)");
                }
            }
            case BACKUP_HISTORY -> where.append(" AND BackupID <> (SELECT * FROM (SELECT COALESCE(MAX(BackupID), 0) "
                    + "FROM BackupRecord WHERE Kind IN ('BACKUP', 'AUTO') AND Result = 'SUCCESS') AS newest)");
            default -> { }
        }
        PreparedStatement ps = c.prepareStatement(verb + log.table + where + tail);
        if (cutoff != null) {
            ps.setObject(1, LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC));
        }
        return ps;
    }

    private static boolean hasTable(Connection c, String schema, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static void deleteQuietly(List<Path> files) {
        for (Path f : files) {
            try {
                java.nio.file.Files.deleteIfExists(f);
            } catch (java.io.IOException ignored) {
                // a leftover copy is harmless
            }
        }
    }
}
