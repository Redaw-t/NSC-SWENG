package nickelsales.auth.demo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import org.mindrot.jbcrypt.BCrypt;

import nickelsales.auth.auth.AuthenticationException;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.auth.LoginResult;
import nickelsales.auth.contract.AuditLogger;
import nickelsales.auth.contract.Session;
import nickelsales.auth.contract.SessionManager;
import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRepository;
import nickelsales.auth.contract.UserRole;
import nickelsales.auth.contract.UserStatus;

/**
 * TEST HARNESS ONLY — lives in test/, NOT in src/.
 *
 * The stub classes below are throwaway stand-ins for the teammates' Sprint 1
 * parts (DATA: UserRepository, SESSION/UI: SessionManager). They exist only
 * so the AuthenticationService can be run and demoed before the real
 * implementations land. Delete this whole folder once they do — src/ is the
 * actual deliverable.
 *
 * Run (from the nickel-sales-auth folder, or just double-click run-demo.bat):
 *   java -cp "out;lib/jbcrypt-0.4.jar" nickelsales.auth.demo.AuthDemo
 *   ... --scripted   runs only the scenarios and exits (smoke test)
 */
public class AuthDemo {

    static final String TEST_PASSWORD = "Password123"; // TEST seeds only

    /** Throwaway stand-in for the DATA role's storage layer. */
    static class StubUserRepository implements UserRepository {
        final Map<String, User> users = new HashMap<>();

        StubUserRepository() {
            String hash = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(10));
            save(new User("NSC-00001", "JCubillan", hash, "Jerick", "Cubillan",
                    UserRole.parseDbRoles("A;C;I"), UserStatus.ACTIVE));
            save(new User("NSC-00002", "JDelaCruz", hash, "Juan", "Dela Cruz",
                    UserRole.parseDbRoles("C"), UserStatus.ACTIVE));
            save(new User("NSC-00003", "MSantaAna", hash, "Maria", "Santa Ana",
                    UserRole.parseDbRoles("I"), UserStatus.ACTIVE));
            save(new User("NSC-00004", "RSantos", hash, "Rhian", "Santos",
                    UserRole.parseDbRoles("C"), UserStatus.INACTIVE));
            save(new User("NSC-00005", "KRodriguez", hash, "Kimberly", "Rodriguez",
                    UserRole.parseDbRoles("C"), UserStatus.LOCKED));
        }

        public User findById(String userId) { return userId == null ? null : users.get(userId); }

        public User findByUsername(String username) {
            if (username == null) return null;
            String key = username.trim().toLowerCase();
            return users.values().stream()
                    .filter(u -> u.getLoginName().toLowerCase().equals(key))
                    .findFirst().orElse(null);
        }

        public void save(User user) { users.put(user.getUserId(), user); }
        public void update(User user) { users.put(user.getUserId(), user); }
        public List<User> findAll() { return List.copyOf(users.values()); }
        public void updatePassword(String userId, String newPasswordHash) {
            User old = users.get(userId);
            if (old == null) {
                throw new RuntimeException("No such user: " + userId);
            }
            users.put(userId, new User(old.getUserId(), newPasswordHash, old.getFirstName(),
                    old.getLastName(), old.getRoles(), old.getStatus()));
        }
    }

    /** Throwaway stand-in for the SESSION/UI role's session management. */
    static class StubSessionManager implements SessionManager {
        final Map<String, Session> sessions = new HashMap<>();

        public Session createSession(String userId) {
            Session session = new Session(UUID.randomUUID().toString(), userId);
            sessions.put(session.getSessionId(), session);
            return session;
        }

        public Session getSession(String sessionId) { return sessions.get(sessionId); }
        public void invalidateSession(String sessionId) { sessions.remove(sessionId); }
        public boolean isSessionValid(String sessionId) { return sessions.containsKey(sessionId); }
    }

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        boolean scriptedOnly = args.length > 0
                && (args[0].equals("-s") || args[0].equals("--scripted"));

        AuthenticationService auth = new AuthenticationService(
                new StubUserRepository(),
                new StubSessionManager(),
                null,
                (event, userId) -> System.out.println("[AUDIT] " + event + " " + userId));

        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Sprint 1 Authentication (AUTH)");
        System.out.println("=================================================================");
        System.out.println(" Seeded accounts (password for all: " + TEST_PASSWORD + ")");
        System.out.println("   NSC-00001  Jerick Cubillan   A;C;I   Active   (admin)");
        System.out.println("   NSC-00002  Juan Dela Cruz    C       Active   (cashier)");
        System.out.println("   NSC-00003  Maria Santa Ana   I       Active   (inventory)");
        System.out.println("   NSC-00004  Test Inactive     C       Inactive");
        System.out.println("   NSC-00005  Test Locked       C       Locked");
        System.out.println("-----------------------------------------------------------------");
        System.out.println(" SCENARIOS");
        System.out.println("-----------------------------------------------------------------");

        // 1. valid login, multi-role user
        scenario("Valid login - admin (NSC-00001)", () -> {
            LoginResult r = auth.login("NSC-00001", TEST_PASSWORD);
            check(r.user().getUserId().equals("NSC-00001"), "wrong user returned");
            check(r.user().hasRole(UserRole.ADMIN) && r.user().hasRole(UserRole.CASHIER)
                    && r.user().hasRole(UserRole.INVENTORY_STAFF), "should hold all three roles");
            check(r.session() != null && r.session().getSessionId() != null, "no session created");
            check(!r.user().getPasswordHash().equals(TEST_PASSWORD), "password must never be plaintext");
        });

        // 2. valid login per single-role users
        scenario("Valid login - cashier (NSC-00002) and inventory (NSC-00003)", () -> {
            check(auth.login("NSC-00002", TEST_PASSWORD).user().hasRole(UserRole.CASHIER), "cashier role missing");
            check(auth.login("NSC-00003", TEST_PASSWORD).user().hasRole(UserRole.INVENTORY_STAFF), "inventory role missing");
        });

        // 3. wrong password
        scenario("Wrong password is rejected", () -> {
            expectCode(() -> auth.login("NSC-00001", "WrongPass1"), "INVALID_CREDENTIALS");
        });

        // 4. unknown user — same error as wrong password
        scenario("Unknown user ID is rejected (same error as wrong password)", () -> {
            expectCode(() -> auth.login("NSC-99999", TEST_PASSWORD), "INVALID_CREDENTIALS");
        });

        // 5. inactive account, CORRECT password
        scenario("Inactive account blocked even with correct password", () -> {
            expectCode(() -> auth.login("NSC-00004", TEST_PASSWORD), "INACTIVE_USER");
        });

        // 6. locked account, CORRECT password
        scenario("Locked account blocked even with correct password", () -> {
            expectCode(() -> auth.login("NSC-00005", TEST_PASSWORD), "LOCKED_USER");
        });

        // 7. boolean validation, never throws
        scenario("validateCredentials() returns booleans", () -> {
            check(auth.validateCredentials("NSC-00002", TEST_PASSWORD), "valid pair should be true");
            check(!auth.validateCredentials("NSC-00002", "nope"), "wrong password should be false");
            check(!auth.validateCredentials("ghost-user", TEST_PASSWORD), "unknown user should be false");
        });

        // 8. session lifecycle
        scenario("getCurrentUser() + logout() session lifecycle", () -> {
            LoginResult r = auth.login("NSC-00002", TEST_PASSWORD);
            check(auth.getCurrentUser(r.session().getSessionId()) != null, "user should be logged in");
            auth.logout(r.session().getSessionId());
            check(auth.getCurrentUser(r.session().getSessionId()) == null, "session should be gone after logout");
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");

        if (!scriptedOnly) {
            runInteractive(auth);
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* --------------------------- interactive UI --------------------------- */

    static void runInteractive(AuthenticationService auth) {
        System.out.println();
        System.out.println(" INTERACTIVE LOGIN (the real login UI replaces this prompt)");
        System.out.println(" Try NSC-00001 / " + TEST_PASSWORD + ", a wrong password, or NSC-00004.");
        System.out.println(" Commands at the User ID prompt:");
        System.out.println("   logout  - end the active session (calls AuthenticationService.logout)");
        System.out.println("   whoami  - show the currently logged-in user for the active session");
        System.out.println("   q       - quit");
        System.out.println("-----------------------------------------------------------------");

        Scanner in = new Scanner(System.in);
        // What the real UI keeps after a successful login; logout/whoami use it.
        String currentSessionId = null;

        while (true) {
            System.out.print(" User ID: ");
            String userId = in.nextLine().trim();
            if (userId.isEmpty() || userId.equalsIgnoreCase("q")) {
                System.out.println(" Bye.");
                break;
            }

            if (userId.equalsIgnoreCase("logout")) {
                if (currentSessionId == null) {
                    System.out.println(" No active session in this demo - log in first.");
                } else {
                    User current = auth.getCurrentUser(currentSessionId);
                    auth.logout(currentSessionId);
                    System.out.println(" LOGOUT OK -> session " + currentSessionId + " ended"
                            + (current != null ? " (" + current.getFullName() + " logged out)." : "."));
                    currentSessionId = null;
                }
                System.out.println("-----------------------------------------------------------------");
                continue;
            }

            if (userId.equalsIgnoreCase("whoami")) {
                User current = currentSessionId == null ? null : auth.getCurrentUser(currentSessionId);
                System.out.println(current == null
                        ? " No active session (never logged in, logged out, or expired)."
                        : " Current user -> " + current.getFullName() + " (" + current.getUserId()
                                + "), roles: " + current.getRoles());
                System.out.println("-----------------------------------------------------------------");
                continue;
            }

            System.out.print(" Password: ");
            String password = in.nextLine();

            try {
                LoginResult r = auth.login(userId, password);
                currentSessionId = r.session().getSessionId();
                System.out.println(" LOGIN OK -> " + r.user().getFullName()
                        + " (" + r.user().getUserId() + ")");
                System.out.println("   roles      : " + r.user().getRoles());
                System.out.println("   session id : " + currentSessionId);
                System.out.println("   role routing (AUTHZ + SESSION/UI's job): would open "
                        + dashboards(r.user()));
            } catch (AuthenticationException e) {
                System.out.println(" LOGIN FAILED -> " + e.getMessage() + "  [" + e.getCode() + "]");
            }
            System.out.println("-----------------------------------------------------------------");
        }
    }

    static String dashboards(User user) {
        StringBuilder sb = new StringBuilder();
        for (UserRole role : user.getRoles()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            switch (role) {
                case ADMIN -> sb.append("Admin dashboard");
                case CASHIER -> sb.append("POS / Cashier screen");
                case INVENTORY_STAFF -> sb.append("Inventory screen");
            }
        }
        return sb.length() > 0 ? sb.toString() : "no role assigned";
    }

    /* ---------------------------- tiny assertions ---------------------------- */

    interface ScenarioBody {
        void run() throws Exception;
    }

    static void scenario(String name, ScenarioBody body) {
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t.getMessage());
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static void expectCode(ScenarioBody loginCall, String expectedCode) throws Exception {
        try {
            loginCall.run();
        } catch (AuthenticationException e) {
            check(e.getCode().equals(expectedCode),
                    "expected error code " + expectedCode + ", got " + e.getCode());
            return;
        }
        throw new IllegalStateException("expected error code " + expectedCode + ", but login succeeded");
    }
}
