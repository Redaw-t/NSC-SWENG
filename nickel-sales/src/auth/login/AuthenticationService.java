package auth.login;

import auth.contract.AuditLogger;
import auth.contract.PasswordPolicy;
import auth.contract.Session;
import auth.contract.SessionManager;
import auth.contract.User;
import auth.contract.UserRepository;
import auth.contract.UserStatus;
import org.mindrot.jbcrypt.BCrypt;

/**
 * AuthenticationService — Sprint 1, owned by the AUTH role.
 * THIS FILE IS THE DELIVERABLE. Everything it uses from the contract
 * package is implemented by other teammates.
 *
 * Class-diagram API (kept 1:1 where possible):
 *   login(username, password)              -> LoginResult   (note 1)
 *   logout(sessionId)                      -> void          (note 2)
 *   validateCredentials(username, password) -> boolean
 *   getCurrentUser(sessionId)              -> User or null
 *
 * All collaborators are injected, so teammates' real implementations drop
 * in without touching this class:
 *   - UserRepository  (DATA role — data layer; required)
 *   - SessionManager  (SESSION/UI role — sessions; optional)
 *   - PasswordPolicy  (shared with UserAccountService; optional)
 *   - AuditLogger     (ILoggingService adapter; optional)
 *
 * Notes / deviations from the class diagram (see README):
 * 1. login() returns LoginResult (user + session), not just User, so the
 *    login screen receives the session id it needs. session is null when no
 *    SessionManager is wired in.
 * 2. logout()/getCurrentUser() take the sessionId (diagram says userId),
 *    because sessions — not users — are what actually expire.
 */
public class AuthenticationService {

    /**
     * A bcrypt hash of a fixed dummy string. When the user ID is not found we
     * still run one bcrypt check against it, so "unknown user" and "wrong
     * password" take the same time to answer. This prevents leaking which
     * user IDs exist through response timing.
     */
    private static final String DUMMY_HASH =
            BCrypt.hashpw("nickel-sales-dummy-comparison", BCrypt.gensalt(10));

    private final UserRepository userRepository;
    private final SessionManager sessionManager;
    private final PasswordPolicy passwordPolicy;
    private final AuditLogger auditLogger;

    public AuthenticationService(UserRepository userRepository) {
        this(userRepository, null, null, null);
    }

    public AuthenticationService(UserRepository userRepository, SessionManager sessionManager,
                                 PasswordPolicy passwordPolicy, AuditLogger auditLogger) {
        if (userRepository == null) {
            throw new IllegalArgumentException("AuthenticationService requires a UserRepository");
        }
        this.userRepository = userRepository;
        this.sessionManager = sessionManager;
        this.passwordPolicy = passwordPolicy;
        this.auditLogger = auditLogger;
    }

    /**
     * Full login flow:
     *   1. look up the user
     *   2. verify the password against its bcrypt hash
     *   3. reject Inactive / Locked accounts (only AFTER the password is
     *      proven, so account status is never revealed to someone who does
     *      not know the password)
     *   4. create a session via the SessionManager
     *   5. audit-log the attempt
     *
     * @throws InvalidCredentialsException unknown user ID or wrong password
     * @throws InactiveUserException       correct password, inactive account
     * @throws LockedUserException         correct password, locked account
     */
    public LoginResult login(String username, String password) throws AuthenticationException {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            bcryptCheck(String.valueOf(password), DUMMY_HASH);
            log("LOGIN_FAILED", String.valueOf(username));
            throw new InvalidCredentialsException();
        }

        if (!bcryptCheck(String.valueOf(password), user.getPasswordHash())) {
            log("LOGIN_FAILED", user.getUserId());
            throw new InvalidCredentialsException();
        }

        if (user.getStatus() == UserStatus.INACTIVE) {
            log("LOGIN_BLOCKED_INACTIVE", user.getUserId());
            throw new InactiveUserException(user.getUserId());
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            log("LOGIN_BLOCKED_LOCKED", user.getUserId());
            throw new LockedUserException(user.getUserId());
        }

        Session session = sessionManager == null ? null : sessionManager.createSession(user.getUserId());

        log("LOGIN_SUCCESS", user.getUserId());
        return new LoginResult(user, session);
    }

    /**
     * Verifies user ID + password WITHOUT creating a session and WITHOUT
     * throwing — always returns true/false. Repository/database problems
     * also count as "not valid" here rather than crashing the caller.
     */
    public boolean validateCredentials(String username, String password) {
        try {
            User user = userRepository.findByUsername(username);
            if (user == null) {
                bcryptCheck(String.valueOf(password), DUMMY_HASH);
                return false;
            }
            return bcryptCheck(String.valueOf(password), user.getPasswordHash());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The currently logged-in User for a session id, or null when the session
     * is missing/expired/logged out. Re-reads the user from the repository so
     * role or status changes take effect on the next call.
     */
    public User getCurrentUser(String sessionId) {
        if (sessionManager == null || sessionId == null || !sessionManager.isSessionValid(sessionId)) {
            return null;
        }
        Session session = sessionManager.getSession(sessionId);
        if (session == null) {
            return null;
        }
        return userRepository.findById(session.getUserId());
    }

    /** Ends the session and audit-logs the logout. Safe on unknown/expired ids. */
    public void logout(String sessionId) {
        // Look the user up only for the audit log. If the database is down this
        // fails, but the session must still be ended, so never let it stop us.
        User user = null;
        try {
            user = getCurrentUser(sessionId);
        } catch (RuntimeException e) {
            // database unavailable: log the session id instead of the user id
        }
        if (sessionManager != null) {
            sessionManager.invalidateSession(sessionId);
        }
        log("LOGOUT", user != null ? user.getUserId() : sessionId);
    }

    /**
     * jBCrypt throws IllegalArgumentException on a malformed stored hash; a
     * corrupt database value must mean "wrong password", not a crash.
     */
    private boolean bcryptCheck(String password, String hash) {
        try {
            return BCrypt.checkpw(password, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void log(String event, String userId) {
        if (auditLogger != null) {
            auditLogger.log(event, userId);
        }
    }
}
