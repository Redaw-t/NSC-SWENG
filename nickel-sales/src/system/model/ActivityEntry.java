package system.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of the activity log: a single recorded action. Read-only once saved; the log is
 * only ever added to.
 *
 * @param logId      database ID; 0 until saved
 * @param createdAt  when it happened (UTC)
 * @param userId     who did it; for a failed login, the login name that was typed; may be null
 * @param terminalId the POS it happened on
 * @param module     see {@link AuditEvent#MODULES}
 * @param action     e.g. LOGIN_SUCCESS
 * @param target     what it was done to; may be null
 * @param details    the rest of the event; may be null
 * @param oldValue   before a change; may be null
 * @param newValue   after a change; may be null
 */
public record ActivityEntry(long logId, Instant createdAt, String userId, String terminalId,
                            String module, String action, String target, String details,
                            String oldValue, String newValue) {

    public ActivityEntry {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(action, "action");
    }

    /** A new (unsaved) entry for {@code event}, done by {@code userId} on {@code terminalId} at {@code at}. */
    public static ActivityEntry of(AuditEvent event, String userId, String terminalId, Instant at) {
        return new ActivityEntry(0, at, userId, terminalId, event.module(), event.action(), event.target(),
                event.details(), event.oldValue(), event.newValue());
    }
}
