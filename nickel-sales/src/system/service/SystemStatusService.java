package system.service;

import system.contract.StatusProvider;
import system.model.BackupRecord;
import system.model.StatusItem;
import system.model.StatusItem.Level;
import system.repository.ActivityLogRepository;
import system.repository.BackupRecordRepository;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Collects each component's health into one status report (Admin only). The ready-made checks
 * below cover the database, the other terminal, unsent sales, the last backup, free disk space
 * and log retention.
 */
public final class SystemStatusService {

    /** How long logs are kept on a terminal (NFR-11). */
    public static final int RETENTION_DAYS = 30;
    /** How many days before that the admin is warned (FR-19). */
    public static final int RETENTION_WARNING_DAYS = 5;
    /** A backup older than this is a warning. */
    public static final int BACKUP_WARNING_DAYS = 7;

    private static final long LOW_DISK_BYTES = 1024L * 1024 * 1024;
    private static final long CRITICAL_DISK_BYTES = 200L * 1024 * 1024;
    private static final int PEER_TIMEOUT_MILLIS = 2000;
    private static final String DATABASE_DOWN = "The local database cannot be reached. Check that MySQL is running.";

    private final SystemAccess access;
    private final List<StatusProvider> providers;

    public SystemStatusService(SystemAccess access, List<StatusProvider> providers) {
        this.access = Objects.requireNonNull(access, "access");
        this.providers = List.copyOf(providers);
    }

    /**
     * Every component's status, in order. One failing check never hides the others. Checking who is
     * asking needs the database, so when that fails the report only says the database is down: this
     * is the screen an admin opens to find that out.
     */
    public List<StatusItem> report(String sessionId) throws SystemException {
        try {
            access.require(sessionId);
        } catch (RuntimeException e) {
            return List.of(new StatusItem("Database", Level.ERROR, "Not connected", DATABASE_DOWN));
        }
        List<StatusItem> out = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            try {
                out.add(providers.get(i).status());
            } catch (RuntimeException e) {
                out.add(new StatusItem("Check " + (i + 1), Level.ERROR, "Unavailable",
                        "This check could not be completed."));
            }
        }
        return out;
    }

    /* ----------------------------- ready-made checks ----------------------------- */

    public static StatusProvider database(BackupService.ConnectionSource source) {
        return () -> {
            long start = System.nanoTime();
            try (Connection c = source.open()) {
                if (c.isValid(2)) {
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    return new StatusItem("Database", Level.OK, "Connected",
                            "MySQL " + c.getMetaData().getDatabaseProductVersion() + " answered in " + millis + " ms.");
                }
            } catch (SQLException e) {
                // reported below
            }
            return new StatusItem("Database", Level.ERROR, "Not connected", DATABASE_DOWN);
        };
    }

    /** @param peerUrl the other terminal's address (NSC_PEER_URL); null or blank if none is set */
    public static StatusProvider otherTerminal(String peerUrl) {
        return () -> {
            if (peerUrl == null || peerUrl.isBlank()) {
                return new StatusItem("Other Terminal", Level.INFO, "Not configured",
                        "No other terminal has been set up for this POS.");
            }
            try {
                HttpURLConnection http = (HttpURLConnection) URI.create(peerUrl.trim()).toURL().openConnection();
                http.setConnectTimeout(PEER_TIMEOUT_MILLIS);
                http.setReadTimeout(PEER_TIMEOUT_MILLIS);
                http.getResponseCode();   // any answer means it is reachable
                http.disconnect();
                return new StatusItem("Other Terminal", Level.OK, "Reachable", peerUrl.trim() + " responded.");
            } catch (IOException | IllegalArgumentException e) {
                return new StatusItem("Other Terminal", Level.WARNING, "Not reachable",
                        peerUrl.trim() + " did not respond. Sales continue locally.");
            }
        };
    }

    /**
     * @param unsyncedSales how many completed sales are waiting to be sent
     * @param connected     true once the Sync module is connected
     */
    public static StatusProvider pendingSync(IntSupplier unsyncedSales, BooleanSupplier connected) {
        return named("Pending Sync", () -> {
            int waiting = unsyncedSales.getAsInt();
            if (!connected.getAsBoolean()) {
                return new StatusItem("Pending Sync", Level.INFO, waiting + " unsent",
                        "Synchronization is not connected yet. Sales are stored locally.");
            }
            return waiting == 0
                    ? new StatusItem("Pending Sync", Level.OK, "None", "All completed sales have been sent.")
                    : new StatusItem("Pending Sync", Level.WARNING, waiting + " unsent",
                            "Completed sales are waiting to be sent to the other terminal.");
        });
    }

    public static StatusProvider lastBackup(BackupRecordRepository records, Clock clock) {
        return named("Last Backup", () -> {
            Optional<BackupRecord> latest = records.latestBackup();
            if (latest.isEmpty()) {
                return new StatusItem("Last Backup", Level.WARNING, "None yet",
                        "No backup has been made. Create one from the Backup & Restore tab.");
            }
            long days = Duration.between(latest.get().createdAt(), clock.instant()).toDays();
            String when = days == 0 ? "Today" : days == 1 ? "1 day ago" : days + " days ago";
            return days >= BACKUP_WARNING_DAYS
                    ? new StatusItem("Last Backup", Level.WARNING, when,
                            "The latest backup is over a week old. Create a new one.")
                    : new StatusItem("Last Backup", Level.OK, when, latest.get().fileName());
        });
    }

    /** @param folder the backup folder; its drive is the one checked */
    public static StatusProvider diskSpace(Supplier<Path> folder) {
        return () -> {
            try {
                Path path = folder.get();
                Path existing = path;
                while (existing != null && !Files.exists(existing)) {
                    existing = existing.getParent();
                }
                if (existing == null) {
                    return new StatusItem("Disk Space", Level.WARNING, "Unknown",
                            "The backup drive is not connected: " + path + ".");
                }
                long free = Files.getFileStore(existing).getUsableSpace();
                Level level = free < CRITICAL_DISK_BYTES ? Level.ERROR : free < LOW_DISK_BYTES ? Level.WARNING : Level.OK;
                return new StatusItem("Disk Space", level, BackupRecord.size(free) + " free",
                        level == Level.OK ? "On the drive that holds the backups."
                                : "Free up space on the backup drive so that backups can be saved.");
            } catch (IOException e) {
                return new StatusItem("Disk Space", Level.WARNING, "Unknown", "Free space could not be read.");
            }
        };
    }

    /** The advance warning that logs are reaching the end of their retention period. */
    public static StatusProvider logRetention(ActivityLogRepository log, Clock clock) {
        return named("Log Retention", () -> {
            Instant now = clock.instant();
            Optional<Instant> oldest = log.oldest();
            if (oldest.isEmpty()) {
                return new StatusItem("Log Retention", Level.OK, "No logs yet",
                        "Logs are kept for " + RETENTION_DAYS + " days on this terminal.");
            }
            long age = Duration.between(oldest.get(), now).toDays();
            long left = RETENTION_DAYS - age;
            if (left <= 0) {
                long over = log.countOlderThan(now.minus(Duration.ofDays(RETENTION_DAYS)));
                return new StatusItem("Log Retention", Level.WARNING, over + " past " + RETENTION_DAYS + " days",
                        "These log entries have passed the retention period. Create a backup to keep a copy.");
            }
            if (left <= RETENTION_WARNING_DAYS) {
                return new StatusItem("Log Retention", Level.WARNING, left + (left == 1 ? " day left" : " days left"),
                        "The oldest logs reach the " + RETENTION_DAYS + "-day retention period soon. "
                                + "Create a backup to keep a copy.");
            }
            return new StatusItem("Log Retention", Level.OK, age + (age == 1 ? " day old" : " days old"),
                    "Age of the oldest log entry. Logs are kept for " + RETENTION_DAYS + " days.");
        });
    }

    /** A check that reads the database: when it cannot, its card keeps its own name. */
    private static StatusProvider named(String name, StatusProvider check) {
        return () -> {
            try {
                return check.status();
            } catch (RuntimeException e) {
                return new StatusItem(name, Level.ERROR, "Unavailable", "This check could not be completed.");
            }
        };
    }
}
