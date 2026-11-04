package system.service;

import auth.contract.AuditLogger;
import system.model.ActivityEntry;
import system.model.AuditEvent;
import system.repository.ActivityLogRepository;

import java.time.Clock;
import java.util.Objects;

/**
 * The app's audit log: every event the modules send through {@link AuditLogger} is saved as
 * one activity log row (and still printed to the console, as before).
 *
 * Logging must never stop the work it records: if the row cannot be saved (database down),
 * the problem is printed and the sale, login or change carries on.
 */
public final class ActivityLogger implements AuditLogger {

    private final ActivityLogRepository repository;
    private final String terminalId;
    private final Clock clock;

    public ActivityLogger(ActivityLogRepository repository, String terminalId) {
        this(repository, terminalId, Clock.systemUTC());
    }

    public ActivityLogger(ActivityLogRepository repository, String terminalId, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void log(String event, String userId) {
        System.out.println("[AUDIT] " + event + " " + userId);
        try {
            AuditEvent parsed = AuditEvent.parse(event);
            String who = userId;
            if (AuditEvent.UNKNOWN_USER.equals(parsed.action())) {
                // No account has this name: keep the typed name as the target, not as a user.
                String typed = userId == null || userId.isBlank() ? "(blank)" : userId.trim();
                parsed = new AuditEvent(parsed.module(), parsed.action(), typed, parsed.details(),
                        parsed.oldValue(), parsed.newValue());
                who = null;
            }
            repository.append(ActivityEntry.of(parsed, who, terminalId, clock.instant()));
        } catch (RuntimeException e) {
            System.out.println("[AUDIT] Could not save to the activity log: " + e.getMessage());
        }
    }
}
