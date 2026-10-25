package auth.session;

import auth.contract.Session;
import auth.contract.SessionManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemorySessionManager — Sprint 1, owned by the SESSION/UI role.
 *
 * Implements the {@link SessionManager} contract. Sessions live in memory
 * only, so they are lost when the app closes (acceptable for Sprint 1; the
 * contract does not care how sessions are stored).
 *
 * Rules:
 *  - Session ids are random UUIDs.
 *  - A session expires after {@code timeout} of inactivity (default 30
 *    minutes, matching "timeoutMinutes" in the class diagram).
 *  - {@link #getSession(String)} counts as activity and resets the timer.
 *    AuthenticationService.getCurrentUser() calls it, so every screen that
 *    asks "who is logged in?" keeps the session alive.
 *  - {@link #isSessionValid(String)} only checks; it never resets the timer.
 *  - Unknown, expired, and logged-out ids never throw: they return
 *    null/false, and invalidateSession ignores them.
 */
public class InMemorySessionManager implements SessionManager {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration timeout;

    public InMemorySessionManager() {
        this(DEFAULT_TIMEOUT);
    }

    /** A custom timeout, e.g. a few hundred milliseconds so tests can check expiry quickly. */
    public InMemorySessionManager(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Session timeout must be a positive duration");
        }
        this.timeout = timeout;
    }

    @Override
    public Session createSession(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Cannot create a session without a user ID");
        }
        Session session = new Session(UUID.randomUUID().toString(), userId);
        sessions.put(session.getSessionId(), session);
        return session;
    }

    @Override
    public Session getSession(String sessionId) {
        Session session = findLive(sessionId);
        if (session != null) {
            session.touch();
        }
        return session;
    }

    @Override
    public void invalidateSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Session session = sessions.remove(sessionId);
        if (session != null) {
            session.markInactive();
        }
    }

    @Override
    public boolean isSessionValid(String sessionId) {
        return findLive(sessionId) != null;
    }

    /** The session if it exists, is active, and has not timed out; otherwise removes it and returns null. */
    private Session findLive(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.isActive() || isExpired(session)) {
            invalidateSession(sessionId);
            return null;
        }
        return session;
    }

    private boolean isExpired(Session session) {
        return LocalDateTime.now().isAfter(session.getLastActivity().plus(timeout));
    }
}