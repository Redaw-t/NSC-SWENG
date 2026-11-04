package system.repository;

import system.model.SyncLogEntry;
import system.model.SyncLogFilter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where sync attempts are kept. Sync records change status, so unlike the other logs this one
 * has an update, but still no delete. Database problems are RuntimeException.
 */
public interface SyncLogRepository {

    /** Saves a new line; returns its ID. */
    long add(SyncLogEntry entry);

    /** Marks line {@code logId} as SYNCED. */
    void markSynced(long logId, Instant at);

    /** Marks line {@code logId} as FAILED and adds one to its retry count. */
    void markFailed(long logId, String errorMessage, Instant at);

    /** Lines matching {@code filter}, newest first, at most {@code limit}. */
    List<SyncLogEntry> find(SyncLogFilter filter, int limit);

    /** When the last send succeeded, if ever. */
    Optional<Instant> lastSuccess();
}
