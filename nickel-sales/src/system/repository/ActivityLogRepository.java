package system.repository;

import system.model.ActivityEntry;
import system.model.ActivityFilter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where activity log entries are kept. Append-only: there is deliberately no update or
 * delete. All the SQL lives in {@link MySqlActivityLogRepository}.
 *
 * Database problems are reported as RuntimeException (like the other MySql repositories).
 */
public interface ActivityLogRepository {

    /** Saves one entry. Text longer than its column is cut to fit. */
    void append(ActivityEntry entry);

    /** Entries matching {@code filter}, newest first, at most {@code limit}. */
    List<ActivityEntry> find(ActivityFilter filter, int limit);

    /** Every user ID that appears in the log, sorted (for the User filter). */
    List<String> userIds();

    /** When the oldest entry was made; empty if the log is empty. */
    Optional<Instant> oldest();

    /** How many entries were made before {@code time}. */
    long countOlderThan(Instant time);
}
