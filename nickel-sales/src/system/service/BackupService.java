package system.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import shared.PhTime;
import system.model.BackupRecord;
import system.repository.BackupRecordRepository;
import system.repository.DatabaseDump;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Backs the local database up to a file and restores it (Admin only).
 *
 * A backup is written to a ".part" file, checked, and only then given its real name, so a file
 * with a backup's name is always a complete backup.
 *
 * A restore replaces the database and MySQL cannot undo that, so it is guarded in three steps:
 * the file is checked first (nothing is touched if it is damaged), a safety backup of the current
 * data is made (the restore stops if that fails), and if the restore fails part-way the safety
 * backup is put back. Every attempt is recorded and audited.
 */
public final class BackupService {

    /** Where connections come from: the app's database, or a test's scratch database. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    public static final String EXTENSION = ".sql.gz";

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US);
    private static final int HISTORY_LIMIT = 500;

    private final ConnectionSource database;
    private final BackupRecordRepository records;
    private final SystemAccess access;
    private final AuditLogger audit;
    private final String terminalId;
    private final Consumer<Path> saveFolder;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Path folder;

    /**
     * @param folder     the backup folder to start with
     * @param saveFolder remembers a new backup folder for the next start
     */
    public BackupService(ConnectionSource database, BackupRecordRepository records, SystemAccess access,
                         AuditLogger audit, String terminalId, Path folder, Consumer<Path> saveFolder, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.records = Objects.requireNonNull(records, "records");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = audit;   // may be null: then nothing is audited
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.saveFolder = Objects.requireNonNull(saveFolder, "saveFolder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The folder new backups go to. */
    public Path folder() {
        return folder;
    }

    /** Changes the folder new backups go to, on this terminal. */
    public void changeFolder(String sessionId, Path newFolder) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(newFolder, "newFolder");
        prepare(newFolder);
        folder = newFolder;
        saveFolder.accept(newFolder);
        log("BACKUP_FOLDER_CHANGED", actor);
    }

    /** The newest backups and restores first. */
    public List<BackupRecord> history(String sessionId) throws SystemException {
        access.require(sessionId);
        try {
            return records.findRecent(HISTORY_LIMIT);
        } catch (RuntimeException e) {
            throw new SystemException("The backup history could not be loaded. Try again.", e);
        }
    }

    /** Backs the database up to {@link #folder()}. The record says where; on failure the message says why. */
    public BackupRecord backup(String sessionId) throws SystemException {
        User actor = access.require(sessionId);
        begin();
        try {
            BackupRecord record = write(folder, BackupRecord.BACKUP, actor);
            save(record);
            log("BACKUP_CREATED target=" + record.fileName(), actor);
            return record;
        } catch (SystemException e) {
            save(failure(BackupRecord.BACKUP, "-", folder, e.getMessage(), actor));
            log("BACKUP_FAILED", actor);
            throw e;
        } finally {
            busy.set(false);
        }
    }

    /**
     * Checks that {@code file} is a whole backup, without changing anything.
     *
     * @return what the file says about itself, for the "Are you sure?" message
     * @throws SystemException it cannot be used; the message says why
     */
    public DatabaseDump.Summary check(String sessionId, Path file) throws SystemException {
        access.require(sessionId);
        return validate(file);
    }

    /**
     * Replaces the database with the backup in {@code file}. The caller must sign the user out
     * afterwards: the restored users and roles may differ from the current ones.
     *
     * @throws SystemException the restore did not happen or was undone; the message says which
     */
    public void restore(String sessionId, Path file) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(file, "file");
        begin();
        try {
            validate(file);

            BackupRecord safety;
            try {
                safety = write(folder, BackupRecord.SAFETY, actor);
            } catch (SystemException e) {
                save(failure(BackupRecord.SAFETY, "-", folder, e.getMessage(), actor));
                throw new SystemException("The restore was cancelled because a safety backup of the current data "
                        + "could not be made. Nothing was changed. " + e.getMessage(), e);
            }

            String name = file.getFileName().toString();
            String target = name.replaceAll("\\s+", "_");
            try {
                load(file);
            } catch (SQLException | IOException e) {
                recover(safety, name, target, actor, e);   // always throws
            }
            // These are saved now because the restore replaced the table they would have been in.
            save(safety);
            save(new BackupRecord(0, clock.instant(), terminalId, BackupRecord.RESTORE, name, parentOf(file),
                    sizeOf(file), BackupRecord.SUCCESS, null, actor.getUserId()));
            log("RESTORE_COMPLETED target=" + target, actor);
        } finally {
            busy.set(false);
        }
    }

    /* -------------------------------- steps -------------------------------- */

    /** The restore failed part-way: puts the safety backup back, and says how that went. */
    private void recover(BackupRecord safety, String name, String target, User actor, Exception cause)
            throws SystemException {
        Path safetyFile = Path.of(safety.folderPath()).resolve(safety.fileName());
        try {
            load(safetyFile);
        } catch (SQLException | IOException e) {
            save(failure(BackupRecord.RESTORE, name, folder, "Failed; safety backup not put back: " + e.getMessage(), actor));
            log("RESTORE_FAILED_UNRECOVERED target=" + target, actor);
            throw new SystemException("The restore failed, and the previous data could not be put back automatically. "
                    + "Do not use the system. Restore this safety backup: " + safetyFile, cause);
        }
        save(safety);
        save(failure(BackupRecord.RESTORE, name, folder, "Failed; previous data put back: " + cause.getMessage(), actor));
        log("RESTORE_FAILED target=" + target, actor);
        throw new SystemException("The restore failed, so the previous data was put back. No data was lost.", cause);
    }

    /** Writes a backup file and returns its record (not saved yet). */
    private BackupRecord write(Path into, String kind, User actor) throws SystemException {
        prepare(into);
        Instant now = clock.instant();
        String prefix = BackupRecord.SAFETY.equals(kind) ? "nickelsales-safety-" : "nickelsales-";
        String name = prefix + terminalId + "-" + FILE_TIME.format(PhTime.of(now)) + EXTENSION;
        Path file = into.resolve(name);
        Path part = into.resolve(name + ".part");
        try {
            try (Connection c = database.open();
                 Writer out = new BufferedWriter(new OutputStreamWriter(
                         new GZIPOutputStream(Files.newOutputStream(part)), StandardCharsets.UTF_8))) {
                DatabaseDump.write(c, out, now, terminalId);
            }
            validate(part);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            return new BackupRecord(0, now, terminalId, kind, name, into.toString(), Files.size(file),
                    BackupRecord.SUCCESS, null, actor.getUserId());
        } catch (SQLException e) {
            throw new SystemException("The database could not be read. Check that MySQL is running and try again.", e);
        } catch (IOException e) {
            throw new SystemException("The backup file could not be saved. Check that the drive is connected and "
                    + "has enough free space.", e);
        } finally {
            try {
                Files.deleteIfExists(part);   // only ever our own unfinished file
            } catch (IOException ignored) {
                // a leftover .part file is harmless: it is never offered as a backup
            }
        }
    }

    private void load(Path file) throws SQLException, IOException {
        try (Connection c = database.open(); BufferedReader in = reader(file)) {
            DatabaseDump.restore(c, in);
        }
    }

    private static DatabaseDump.Summary validate(Path file) throws SystemException {
        try (BufferedReader in = reader(file)) {
            return DatabaseDump.validate(in);
        } catch (IllegalArgumentException e) {
            throw new SystemException(e.getMessage(), e);
        } catch (ZipException | EOFException e) {
            throw new SystemException("This file is not a complete Nickel Sales Center backup.", e);
        } catch (IOException e) {
            throw new SystemException("The backup file could not be read. Check that the drive is connected.", e);
        }
    }

    private static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8));
    }

    private static void prepare(Path into) throws SystemException {
        try {
            Files.createDirectories(into);
        } catch (IOException e) {
            throw new SystemException("The backup folder cannot be used. Check that the drive is connected.", e);
        }
        if (!Files.isWritable(into)) {
            throw new SystemException("The backup folder cannot be written to. Choose another folder.");
        }
    }

    private void begin() throws SystemException {
        if (!busy.compareAndSet(false, true)) {
            throw new SystemException("A backup or restore is already running. Wait for it to finish.");
        }
    }

    private BackupRecord failure(String kind, String name, Path where, String message, User actor) {
        return new BackupRecord(0, clock.instant(), terminalId, kind, name, where.toString(), null,
                BackupRecord.FAILED, message, actor.getUserId());
    }

    /** History is a record of the work, not the work itself: failing to save it never fails a backup. */
    private void save(BackupRecord record) {
        try {
            records.add(record);
        } catch (RuntimeException e) {
            System.out.println("[BACKUP] Could not save to the backup history: " + e.getMessage());
        }
    }

    private void log(String event, User actor) {
        if (audit != null) {
            audit.log(event, actor.getUserId());
        }
    }

    private static String parentOf(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        return parent == null ? "" : parent.toString();
    }

    private static Long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** The newest successful backup, for the status screen. */
    public Optional<BackupRecord> latestBackup() {
        return records.latestBackup();
    }
}
