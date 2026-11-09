package system.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import shared.PhTime;
import system.contract.SyncLogWriter;
import system.contract.SyncRetryHandler;
import system.model.SyncLogEntry;
import system.model.SyncLogFilter;
import system.repository.SyncLogRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * The Sync Logs tab (Admin only) and, as {@link SyncLogWriter}, where the Sync module records
 * its attempts. Retry only forwards the request: the retry logic stays in Sync.
 */
public final class SyncLogService implements SyncLogWriter {

    /** The most rows one search returns; narrow the dates to see older ones. */
    public static final int MAX_ROWS = 5000;

    private final SyncLogRepository repository;
    private final SystemAccess access;
    private final AuditLogger audit;
    private final String terminalId;
    private final IntSupplier unsyncedSales;
    private final Clock clock;
    private volatile SyncRetryHandler retryHandler;   // null until the Sync module is connected

    /** @param unsyncedSales how many completed sales are waiting to be sent */
    public SyncLogService(SyncLogRepository repository, SystemAccess access, AuditLogger audit, String terminalId,
                          IntSupplier unsyncedSales) {
        this(repository, access, audit, terminalId, unsyncedSales, Clock.systemUTC());
    }

    public SyncLogService(SyncLogRepository repository, SystemAccess access, AuditLogger audit, String terminalId,
                          IntSupplier unsyncedSales, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = audit;   // may be null: then retries and exports are not logged
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.unsyncedSales = Objects.requireNonNull(unsyncedSales, "unsyncedSales");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Connects the Retry button to the Sync module. */
    public void setRetryHandler(SyncRetryHandler handler) {
        this.retryHandler = handler;
    }

    /** True once the Sync module has connected itself. */
    public boolean syncConnected() {
        return retryHandler != null;
    }

    /* ------------------------- for the Sync Logs tab ------------------------- */

    /** Lines matching {@code filter}, newest first, at most {@link #MAX_ROWS}. */
    public List<SyncLogEntry> find(String sessionId, SyncLogFilter filter) throws SystemException {
        access.require(sessionId);
        try {
            return repository.find(filter == null ? SyncLogFilter.all() : filter, MAX_ROWS);
        } catch (RuntimeException e) {
            throw new SystemException("The sync log could not be loaded. Try again.", e);
        }
    }

    /** How many completed sales are waiting to be sent. */
    public int unsyncedSales(String sessionId) throws SystemException {
        access.require(sessionId);
        try {
            return unsyncedSales.getAsInt();
        } catch (RuntimeException e) {
            throw new SystemException("The number of unsent sales could not be loaded. Try again.", e);
        }
    }

    /** When the last send succeeded, if ever. */
    public Optional<Instant> lastSuccess(String sessionId) throws SystemException {
        access.require(sessionId);
        try {
            return repository.lastSuccess();
        } catch (RuntimeException e) {
            throw new SystemException("The last sync time could not be loaded. Try again.", e);
        }
    }

    /** Asks Sync to send a failed record again. */
    public void retry(String sessionId, SyncLogEntry entry) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(entry, "entry");
        SyncRetryHandler handler = retryHandler;
        if (handler == null) {
            throw new SystemException("Synchronization is not connected on this terminal yet.");
        }
        if (!SyncLogEntry.FAILED.equals(entry.status())) {
            throw new SystemException("Only failed sync attempts can be retried.");
        }
        try {
            handler.retry(entry.logId(), entry.dataType(), entry.recordId());
        } catch (Exception e) {
            throw new SystemException(e.getMessage() == null ? "The retry could not be requested." : e.getMessage(), e);
        }
        if (audit != null) {
            audit.log("SYNC_RETRY_REQUESTED target=" + entry.recordId(), actor.getUserId());
        }
    }

    /** Writes {@code rows} (what the screen is showing) to a CSV file; returns how many. */
    public int exportCsv(String sessionId, List<SyncLogEntry> rows, Path file) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(file, "file");
        List<List<String>> lines = new ArrayList<>();
        for (SyncLogEntry e : rows) {
            lines.add(Arrays.asList(ActivityLogService.CSV_TIME.format(PhTime.of(e.createdAt())), e.sourceTerminal(),
                    e.targetTerminal(), e.dataType(), e.recordId(), e.statusLabel(),
                    String.valueOf(e.retryCount()), e.errorMessage()));
        }
        Csv.write(file, List.of("Date / Time", "Source", "Target", "Data Type", "Record ID", "Status", "Retries",
                "Error"), lines);
        if (audit != null) {
            audit.log("LOGS_EXPORTED target=SyncLog " + rows.size() + " rows", actor.getUserId());
        }
        return rows.size();
    }

    /* ---------------------------- for the Sync module ---------------------------- */

    @Override
    public long attempt(String targetTerminal, String dataType, String recordId) {
        return attempt(terminalId, targetTerminal, dataType, recordId);
    }

    @Override
    public long attempt(String sourceTerminal, String targetTerminal, String dataType, String recordId) {
        Instant now = clock.instant();
        try {
            return repository.add(new SyncLogEntry(0, now, now, sourceTerminal, targetTerminal, dataType, recordId,
                    SyncLogEntry.PENDING, 0, null));
        } catch (RuntimeException e) {
            System.out.println("[SYNC] Could not save to the sync log: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public void succeeded(long logId) {
        try {
            repository.markSynced(logId, clock.instant());
        } catch (RuntimeException e) {
            System.out.println("[SYNC] Could not update the sync log: " + e.getMessage());
        }
    }

    @Override
    public void failed(long logId, String errorMessage) {
        try {
            repository.markFailed(logId, errorMessage, clock.instant());
        } catch (RuntimeException e) {
            System.out.println("[SYNC] Could not update the sync log: " + e.getMessage());
        }
    }
}
