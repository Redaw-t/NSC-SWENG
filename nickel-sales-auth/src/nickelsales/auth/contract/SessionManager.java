package nickelsales.auth.contract;

/**
 * CONTRACT ONLY — implemented by the SESSION/UI role.
 *
 * AuthenticationService calls only these four methods after a successful
 * login. Any implementation (in-memory, database/UI-backed) works.
 */
public interface SessionManager {

    Session createSession(String userId);

    /** The session, or null when unknown/expired. */
    Session getSession(String sessionId);

    void invalidateSession(String sessionId);

    boolean isSessionValid(String sessionId);
}
