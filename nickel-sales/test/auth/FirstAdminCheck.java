package auth;

import auth.account.AccountException;
import auth.account.FirstAdminService;
import auth.account.PassPolicy;
import auth.contract.User;
import auth.contract.UserRepository;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import org.mindrot.jbcrypt.BCrypt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SELF-TEST for Installation Setup's Create Admin Account step (Main POS): the database starts with no
 * users, and the admin typed in setup becomes NSC-00001 with every role, a bcrypt password and Active
 * status. No database needed (an in-memory user store).
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" auth.FirstAdminCheck
 */
public class FirstAdminCheck {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Installation Setup: first admin account");
        System.out.println("=================================================================");

        check("The username is made from the names, like Add user: first initial + last name", () -> {
            FirstAdminService s = service(new MemoryUsers(), null);
            expect(s.usernameFor("Jerick", "Cubillan").equals("JCubillan"), "JCubillan");
            expect(s.usernameFor(" jerick ", "Chua Cubillan").equals("JChuaCubillan"), "spaces dropped, initial upper case");
            expect(s.usernameFor("Ma.", "dela Cruz-Santos").equals("MdelaCruzSantos"), "symbols dropped");
        });

        check("The admin becomes NSC-00001, Active, with every role, that username and a bcrypt password", () -> {
            MemoryUsers users = new MemoryUsers();
            List<String> audit = new ArrayList<>();
            User admin = service(users, audit).create(" Jerick ", "Cubillan", "Secret123", "Secret123");
            expect(admin.getUserId().equals("NSC-00001"), "ID NSC-00001");
            expect(admin.getLoginName().equals("JCubillan"), "generated username");
            User saved = users.findByUsername("jcubillan");
            expect(saved != null && saved.getUserId().equals("NSC-00001"), "saved, found by username");
            expect(saved.getFirstName().equals("Jerick") && saved.getLastName().equals("Cubillan"), "names trimmed");
            expect(saved.getStatus() == UserStatus.ACTIVE, "active");
            expect(saved.getRoles().containsAll(List.of(UserRole.ADMIN, UserRole.CASHIER, UserRole.INVENTORY_STAFF)),
                    "every role");
            expect(!saved.getPasswordHash().equals("Secret123") && BCrypt.checkpw("Secret123", saved.getPasswordHash()),
                    "bcrypt hash of the password");
            expect(audit.size() == 1 && audit.get(0).startsWith("USER_CREATED NSC-00001"), "logged");
        });

        check("Names and password are checked, with a message ready to show", () -> {
            FirstAdminService s = service(new MemoryUsers(), null);
            refused(() -> s.usernameFor("", "Cubillan"), "first name");
            refused(() -> s.usernameFor("Jerick", " "), "last name");
            refused(() -> s.usernameFor("Jerick", "--"), "letters or numbers");
            refused(() -> s.usernameFor("x".repeat(31), "Cubillan"), "at most 30");
            refused(() -> s.create("Jerick", "Cubillan", "", ""), "password");
            refused(() -> s.create("Jerick", "Cubillan", "short", "short"), "8-72");
            refused(() -> s.create("Jerick", "Cubillan", "secret123", "secret123"), "uppercase");
            refused(() -> s.create("Jerick", "Cubillan", "Secret123", "Secret124"), "do not match");
        });

        check("Running setup again replaces NSC-00001 (an interrupted setup), never adds a second admin", () -> {
            MemoryUsers users = new MemoryUsers();
            FirstAdminService s = service(users, null);
            s.create("Jerick", "Cubillan", "Secret123", "Secret123");
            expect(s.usernameFor("Jerick", "Cubillan").equals("JCubillan"), "its own username is not 'taken'");
            s.create("Maria", "Santos", "Better456", "Better456");
            expect(users.findAll().size() == 1, "still one user");
            expect(users.findById("NSC-00001").getLoginName().equals("MSantos"), "username from the second try");
            expect(users.findByUsername("JCubillan") == null, "old username gone");
        });

        check("A username another user already has gets a number, as in Add user", () -> {
            MemoryUsers users = new MemoryUsers();
            users.save(new User("NSC-00007", "JCubillan", "x", "Joy", "Cubillan", java.util.Set.of(UserRole.CASHIER),
                    UserStatus.ACTIVE));
            expect(service(users, null).usernameFor("Jerick", "Cubillan").equals("JCubillan2"), "JCubillan2");
            expect(service(users, null).create("Jerick", "Cubillan", "Secret123", "Secret123").getLoginName()
                    .equals("JCubillan2"), "saved as JCubillan2");
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    static FirstAdminService service(MemoryUsers users, List<String> audit) {
        return new FirstAdminService(users, new PassPolicy(),
                audit == null ? null : (event, userId) -> audit.add(event));
    }

    /** An empty user store, like a new database (no seed users). */
    static final class MemoryUsers implements UserRepository {
        private final Map<String, User> users = new LinkedHashMap<>();

        public User findById(String userId) {
            return users.get(userId);
        }

        public User findByUsername(String username) {
            String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
            return users.values().stream().filter(x -> x.getLoginName().toLowerCase(Locale.ROOT).equals(u))
                    .findFirst().orElse(null);
        }

        public void save(User user) {
            if (users.putIfAbsent(user.getUserId(), user) != null) {
                throw new RuntimeException("exists: " + user.getUserId());
            }
        }

        public void update(User user) {
            if (users.replace(user.getUserId(), user) == null) {
                throw new RuntimeException("no such user: " + user.getUserId());
            }
        }

        public void updatePassword(String userId, String hash) {
            throw new UnsupportedOperationException();
        }

        public List<User> findAll() {
            return List.copyOf(users.values());
        }
    }

    interface Body {
        void run() throws Exception;
    }

    interface Attempt {
        void run() throws AccountException;
    }

    static void refused(Attempt attempt, String messagePart) {
        try {
            attempt.run();
        } catch (AccountException e) {
            if (!e.getMessage().toLowerCase(Locale.ROOT).contains(messagePart.toLowerCase(Locale.ROOT))) {
                throw new AssertionError("message \"" + e.getMessage() + "\" should mention " + messagePart);
            }
            return;
        }
        throw new AssertionError("should have been refused: " + messagePart);
    }

    static void expect(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void check(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println(" [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println(" [FAIL] " + name + "  ->  " + t);
        }
    }
}
