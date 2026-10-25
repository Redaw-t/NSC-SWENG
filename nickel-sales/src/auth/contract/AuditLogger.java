package auth.contract;

/**
 * CONTRACT ONLY — implemented by whoever owns the logging service
 * (ILoggingService in the class diagram).
 *
 * AuthenticationService emits these events; a console implementation is
 * enough for Sprint 1, a database-backed one can come later without
 * touching authentication.
 *
 * Events currently emitted:
 *   LOGIN_SUCCESS, LOGIN_FAILED, LOGIN_BLOCKED_INACTIVE,
 *   LOGIN_BLOCKED_LOCKED, LOGOUT, PASSWORD_CHANGED, PASSWORD_CHANGE_FAILED
 */
public interface AuditLogger {

    void log(String event, String userId);
}
