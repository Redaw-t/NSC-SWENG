package system.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import shared.PhTime;
import system.model.ActivityDescription;
import system.model.ActivityEntry;
import system.model.ActivityFilter;
import system.model.AuditEvent;
import system.repository.ActivityLogRepository;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads, filters and exports the activity log (Admin only). It never edits or deletes an
 * entry: the log is append-only, and {@link ActivityLogger} is the only writer.
 */
public final class ActivityLogService {

    /** The most rows one search returns; narrow the dates to see older ones. */
    public static final int MAX_ROWS = 5000;

    static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final ActivityLogRepository repository;
    private final SystemAccess access;
    private final AuditLogger audit;

    public ActivityLogService(ActivityLogRepository repository, SystemAccess access, AuditLogger audit) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = audit;   // may be null: then exports are not logged
    }

    /** Entries matching {@code filter}, newest first, at most {@link #MAX_ROWS}. */
    public List<ActivityEntry> find(String sessionId, ActivityFilter filter) throws SystemException {
        access.require(sessionId);
        try {
            return repository.find(filter == null ? ActivityFilter.all() : filter, MAX_ROWS);
        } catch (RuntimeException e) {
            throw new SystemException("The activity log could not be loaded. Try again.", e);
        }
    }

    /** Every user ID in the log, for the User filter. */
    public List<String> userIds(String sessionId) throws SystemException {
        access.require(sessionId);
        try {
            return repository.userIds();
        } catch (RuntimeException e) {
            throw new SystemException("The list of users could not be loaded. Try again.", e);
        }
    }

    /**
     * Writes {@code rows} (what the screen is showing) to a CSV file that opens in Excel.
     *
     * @param userName the person's name for a user ID, or null if unknown
     * @return how many rows were written
     */
    public int exportCsv(String sessionId, List<ActivityEntry> rows, Path file,
                         Function<String, String> userName) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(file, "file");
        List<List<String>> lines = new ArrayList<>();
        for (ActivityEntry e : rows) {
            String name = e.userId() == null || userName == null ? null : userName.apply(e.userId());
            lines.add(Arrays.asList(CSV_TIME.format(PhTime.of(e.createdAt())), e.userId(), name, e.terminalId(),
                    AuditEvent.moduleLabel(e.module()), e.action(), e.target(), e.oldValue(), e.newValue(), ActivityDescription.describe(e)));
        }
        Csv.write(file, List.of("Date / Time", "User ID", "User", "Terminal", "Module", "Action", "Target",
                "Old Value", "New Value", "Details"), lines);
        if (audit != null) {
            audit.log("LOGS_EXPORTED target=ActivityLog " + rows.size() + " rows", actor.getUserId());
        }
        return rows.size();
    }
}
