package nickelsales.auth.auth;

import nickelsales.auth.contract.Session;
import nickelsales.auth.contract.User;

/**
 * What {@link AuthenticationService#login(String, String)} returns: the
 * authenticated user plus the session that was just created (session is
 * null when no SessionManager is wired in).
 *
 * The class diagram says login returns User; we wrap it so the login screen
 * also receives the sessionId it needs for getCurrentUser()/logout().
 */
public record LoginResult(User user, Session session) {
}
