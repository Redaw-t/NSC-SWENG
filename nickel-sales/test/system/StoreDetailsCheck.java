package system;

import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import pos.receipt.ReceiptBuilder;
import pos.receipt.StoreInfo;
import system.model.AuditEvent;
import system.model.StoreDetailsRecord;
import system.repository.StoreDetailsRepository;
import system.service.StoreDetailsService;
import system.service.SystemAccess;
import system.service.SystemException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SELF-TEST for the store details on receipts (StoreDetailsService): every change is a new row of the
 * log with who performed it, the newest row is the one in use, only an admin can change them in
 * Settings, and Installation Setup records the first admin as the performer. No database needed (an
 * in-memory log).
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" system.StoreDetailsCheck
 */
public class StoreDetailsCheck {

    static int passed = 0;
    static int failed = 0;

    static final Instant NOW = Instant.parse("2026-11-09T02:00:00Z");
    static final StoreInfo FALLBACK = new StoreInfo("NICKEL SALES CENTER", StoreInfo.NICKEL_ADDRESS,
            StoreInfo.NICKEL_PROPRIETOR, StoreInfo.NICKEL_TIN, "");
    static final User ADMIN = new User("NSC-00002", "admin2", "x", "Second", "Admin",
            java.util.EnumSet.of(UserRole.ADMIN), UserStatus.ACTIVE);
    static final String ADMIN_SESSION = "admin-session";
    static final String CASHIER_SESSION = "cashier-session";

    /** Admin session: allowed; cashier session: refused like SystemAccess.of does. */
    static final SystemAccess ACCESS = sessionId -> {
        if (ADMIN_SESSION.equals(sessionId)) {
            return ADMIN;
        }
        throw new SystemException("You do not have permission to view system logs.");
    };

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Store details (receipt header) log");
        System.out.println("=================================================================");

        check("Nothing saved yet: the settings file's details (Nickel's own) are used", () -> {
            Fixture f = new Fixture();
            expectTrue(f.service.current().equals(FALLBACK), "fallback");
            expectTrue(!f.service.registered(), "not registered");
        });

        check("Installation Setup: the first row is performed by the first admin (NSC-00001)", () -> {
            Fixture f = new Fixture();
            StoreDetailsRecord r = f.service.registerAtInstallation("NSC-00001", " Peña Hardware ", "Naga City",
                    "Juan Peña", "000-111", "");
            expectText(r.performedBy(), "NSC-00001");
            expectText(r.terminalId(), "T01");
            expectTrue(r.performedAt().equals(NOW), "time");
            expectText(r.store().name(), "Peña Hardware");        // trimmed
            expectText(f.service.current().name(), "Peña Hardware");
            expectTrue(f.service.registered(), "registered");
            expectTrue(f.audit.size() == 1 && f.audit.get(0).equals("STORE_DETAILS_REGISTERED Peña Hardware by NSC-00001"),
                    "logged: " + f.audit);
        });

        check("Each change adds a row (nothing is overwritten) and the newest is the one in use", () -> {
            Fixture f = new Fixture();
            f.service.registerAtInstallation("NSC-00001", "Store A", "", "", "", "");
            f.service.change(ADMIN_SESSION, "Store B", "Address B", "", "", "");
            f.service.change(ADMIN_SESSION, "Store C", "Address B", "", "123", "");
            List<StoreDetailsRecord> history = f.service.history(ADMIN_SESSION);
            expectTrue(history.size() == 3, "3 rows: " + history.size());
            expectText(history.get(0).store().name(), "Store C");   // newest first
            expectText(history.get(2).store().name(), "Store A");   // the first is still there
            expectText(history.get(0).performedBy(), "NSC-00002");
            expectText(history.get(2).performedBy(), "NSC-00001");
            expectText(f.service.current().name(), "Store C");
            expectText(f.service.current().tin(), "123");
        });

        check("Only an admin can change the details or see the log", () -> {
            Fixture f = new Fixture();
            expectThrows(() -> f.service.change(CASHIER_SESSION, "Mine", "", "", "", ""), SystemException.class);
            expectThrows(() -> f.service.history(CASHIER_SESSION), SystemException.class);
            expectTrue(f.repo.rows.isEmpty(), "nothing saved");
        });

        check("Details are checked: store name required, column sizes, no change refused", () -> {
            Fixture f = new Fixture();
            expectMessage(() -> f.service.change(ADMIN_SESSION, "  ", "", "", "", ""), "Enter the store name.");
            expectMessage(() -> f.service.registerAtInstallation("NSC-00001", "", "", "", "", ""),
                    "Enter the store name.");
            expectMessage(() -> f.service.change(ADMIN_SESSION, "x".repeat(101), "", "", "", ""),
                    "The store name can be at most 100 characters.");
            expectMessage(() -> f.service.change(ADMIN_SESSION, "Ok", "", "", "1".repeat(31), ""),
                    "The TIN can be at most 30 characters.");
            f.service.change(ADMIN_SESSION, "Same", "", "", "", "");
            expectMessage(() -> f.service.change(ADMIN_SESSION, " Same ", "", "", "", ""),
                    "Nothing was changed. Edit a detail, then save.");
            expectMessage(() -> f.service.registerAtInstallation(" ", "Store", "", "", "", ""),
                    "Create the admin account first.");
            expectTrue(f.repo.rows.size() == 1, "only the valid change saved");
        });

        check("Receipts use the newest row as soon as it is saved, and the last one if the database is down", () -> {
            Fixture f = new Fixture();
            ReceiptBuilder receipts = new ReceiptBuilder(f.service::current, id -> null, id -> List.of());
            expectTrue(receipts != null, "builder");
            f.service.change(ADMIN_SESSION, "Before", "", "", "", "");
            expectText(f.service.current().name(), "Before");
            f.service.change(ADMIN_SESSION, "After", "", "", "", "");
            expectText(f.service.current().name(), "After");
            f.repo.down = true;
            expectText(f.service.current().name(), "After");       // still prints
            expectThrows(() -> f.service.change(ADMIN_SESSION, "Later", "", "", "", ""), SystemException.class);
        });

        check("Store detail changes show under System in the activity log", () ->
                expectText(AuditEvent.moduleOf("STORE_DETAILS_CHANGED"), AuditEvent.SYSTEM));

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    static final class Fixture {
        final MemoryLog repo = new MemoryLog();
        final List<String> audit = new ArrayList<>();
        final StoreDetailsService service = new StoreDetailsService(repo, ACCESS,
                (event, userId) -> audit.add(event + " by " + userId), "T01", FALLBACK,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The StoreDetails table in memory; {@code down} makes it fail like a stopped MySQL. */
    static final class MemoryLog implements StoreDetailsRepository {
        final List<StoreDetailsRecord> rows = new ArrayList<>();
        boolean down;

        @Override
        public StoreDetailsRecord add(StoreDetailsRecord r) {
            failIfDown();
            StoreDetailsRecord saved = new StoreDetailsRecord(rows.size() + 1, r.store(), r.performedBy(),
                    r.performedAt(), r.terminalId());
            rows.add(saved);
            return saved;
        }

        @Override
        public Optional<StoreDetailsRecord> latest() {
            failIfDown();
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(rows.size() - 1));
        }

        @Override
        public List<StoreDetailsRecord> history(int limit) {
            failIfDown();
            List<StoreDetailsRecord> out = new ArrayList<>(rows);
            java.util.Collections.reverse(out);
            return out.subList(0, Math.min(limit, out.size()));
        }

        private void failIfDown() {
            if (down) {
                throw new RuntimeException("database down");
            }
        }
    }

    interface Body {
        void run() throws Exception;
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

    static void expectText(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }

    static void expectTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void expectMessage(Body body, String message) {
        try {
            body.run();
        } catch (SystemException e) {
            expectText(e.getMessage(), message);
            return;
        } catch (Exception e) {
            throw new AssertionError("expected SystemException but got " + e);
        }
        throw new AssertionError("expected \"" + message + "\"");
    }

    static void expectThrows(Body body, Class<? extends Throwable> type) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
