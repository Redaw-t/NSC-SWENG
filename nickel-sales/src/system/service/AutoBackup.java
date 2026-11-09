package system.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Backs the database up on its own every hour while the app is open (BackupService.autoBackup), on a
 * background thread. Only the newest automatic backup is kept: each new one replaces the one before.
 *
 * The file goes to {@link #defaultFolder()}: on an installed PC
 * C:\ProgramData\NickelSales\backups\auto, a hidden system folder, away from Documents and the
 * desktop, so it isn't deleted by accident (manual backups still go to the folder chosen in
 * Settings &gt; Backup &amp; Restore). Every attempt is in the backup history as "Auto Backup".
 */
public final class AutoBackup {

    /** How often. */
    public static final Duration EVERY = Duration.ofHours(1);
    /** The first one after the app opens, if the last one is older than {@link #EVERY} (not during start-up). */
    static final Duration FIRST_AFTER = Duration.ofMinutes(2);
    /** A backup or restore was running at the hour: try again this much later. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    private final BackupService backups;
    private final Path folder;
    private final Clock clock;
    private ScheduledExecutorService thread;

    public AutoBackup(BackupService backups, Path folder, Clock clock) {
        this.backups = Objects.requireNonNull(backups, "backups");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Where automatic backups go: on an installed PC C:\ProgramData\NickelSales\backups\auto; in a
     * development run %LOCALAPPDATA%\NickelSales\backups\auto (never mixed with an installed copy's).
     */
    public static Path defaultFolder() {
        String programData = System.getenv("ProgramData");
        if (shared.Settings.isInstalledApp() && programData != null && !programData.isBlank()) {
            return Path.of(programData, "NickelSales", "backups", "auto");
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local, "NickelSales", "backups", "auto");
        }
        return Path.of(System.getProperty("user.home"), ".nickelsales", "backups", "auto");
    }

    public Path folder() {
        return folder;
    }

    /** Starts the hourly backups (the first within {@link #FIRST_AFTER} if the last one is over an hour old). */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-backup");
            t.setDaemon(true);   // never keeps the app from closing
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        Duration first = firstDelay(newest().orElse(null), clock.instant());
        thread.scheduleAtFixedRate(this::runOnce, first.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
        System.out.println("[BACKUP] automatic backup every hour to " + folder + ", next in "
                + first.toMinutes() + " min");
    }

    public synchronized void stop() {
        if (thread != null) {
            thread.shutdownNow();
            thread = null;
        }
    }

    /** One automatic backup now; if a backup or restore is running, once more a few minutes later. */
    void runOnce() {
        try {
            if (backups.autoBackup(folder).isEmpty()) {
                ScheduledExecutorService t = thread;
                if (t != null) {
                    t.schedule(() -> backups.autoBackup(folder), RETRY_AFTER.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("[BACKUP] automatic backup: " + e);   // never stops the hourly schedule
        }
    }

    /** When the first one is due: an hour after the newest one, but not before {@link #FIRST_AFTER} from now. */
    public static Duration firstDelay(Instant newest, Instant now) {
        if (newest == null) {
            return FIRST_AFTER;
        }
        Duration left = Duration.between(now, newest.plus(EVERY));
        return left.compareTo(FIRST_AFTER) < 0 ? FIRST_AFTER : left.compareTo(EVERY) > 0 ? EVERY : left;
    }

    /** When the newest automatic backup in the folder was made; empty if there is none. */
    private Optional<Instant> newest() {
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        try (var files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().startsWith(BackupService.AUTO_PREFIX))
                    .map(AutoBackup::modified)
                    .max(Comparator.naturalOrder());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return FileTime.fromMillis(0).toInstant();
        }
    }
}
