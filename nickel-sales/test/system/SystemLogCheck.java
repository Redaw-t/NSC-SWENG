package system;

import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import system.model.ActivityDescription;
import system.model.ActivityEntry;
import system.model.ActivityFilter;
import system.model.AuditEvent;
import system.repository.ActivityLogRepository;
import system.repository.MySqlActivityLogRepository;
import system.service.ActivityLogService;
import system.service.ActivityLogger;
import system.service.SystemException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SELF-TEST for the Audit &amp; System activity log: how audit events are split into columns,
 * that logging never stops the work it records, the Admin-only service and its CSV export,
 * and (against the local database) that an entry is saved and found again.
 *
 * Run after any run-demo build, from nickel-sales/:
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar" system.SystemLogCheck
 * (macOS/Linux: ':' instead of ';'). Exit code 1 on failure. The database part needs the
 * ActivityLog table: run the database setup script first.
 */
public class SystemLogCheck {

    static int passed = 0;
    static int failed = 0;

    static final Instant NOW = Instant.parse("2026-10-29T02:42:00Z");   // 10:42 in the Philippines
    static final User ADMIN = new User("NSC-00001", "admin", "x", "Test", "Admin",
            java.util.EnumSet.of(UserRole.ADMIN), UserStatus.ACTIVE);

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - Audit & System checks (activity log)");
        System.out.println("=================================================================");

        check("Event: a plain login event has only an action", () -> {
            AuditEvent e = AuditEvent.parse("LOGIN_SUCCESS");
            expectText(e.action(), "LOGIN_SUCCESS");
            expectText(e.module(), AuditEvent.LOGIN);
            expectTrue(e.target() == null && e.details() == null && e.oldValue() == null, "nothing else");
        });

        check("Event: target= names the target", () -> {
            AuditEvent e = AuditEvent.parse("PRODUCT_UPDATED target=P-0001");
            expectText(e.target(), "P-0001");
            expectText(e.module(), AuditEvent.PRODUCTS);
            expectTrue(e.details() == null, "no details");
        });

        check("Event: price pairs become old and new values", () -> {
            AuditEvent e = AuditEvent.parse("PRICE_CHANGED target=P-0001 retail=250.00>260.00 wholesale=none>240.00");
            expectText(e.target(), "P-0001");
            expectText(e.oldValue(), "Retail 250.00, Wholesale none");
            expectText(e.newValue(), "Retail 260.00, Wholesale 240.00");
            expectTrue(e.details() == null, "no details left");
        });

        check("Event: without target=, the second word is the target", () -> {
            AuditEvent e = AuditEvent.parse("STOCK_ADJUSTED P-0002 to 10 COUNT_CORRECTION");
            expectText(e.target(), "P-0002");
            expectText(e.details(), "to 10 COUNT_CORRECTION");
            expectText(e.module(), AuditEvent.INVENTORY);
            expectText(AuditEvent.parse("STOCK_OUT P-0001 -2 DAMAGE").details(), "-2 DAMAGE");
            expectText(AuditEvent.parse("SALE_COMPLETED T01-2026-000143").target(), "T01-2026-000143");
        });

        check("Event: every module is recognised; blank and unknown events are kept", () -> {
            expectText(AuditEvent.moduleOf("LOGIN_BLOCKED_LOCKED"), AuditEvent.LOGIN);
            expectText(AuditEvent.moduleOf("PASSWORD_CHANGED"), AuditEvent.LOGIN);
            expectText(AuditEvent.moduleOf("PASSWORD_RESET"), AuditEvent.USERS);
            expectText(AuditEvent.moduleOf("USER_CREATED"), AuditEvent.USERS);
            expectText(AuditEvent.moduleOf("SUPPLIER_UPDATED"), AuditEvent.PRODUCTS);
            expectText(AuditEvent.moduleOf("REORDER_LEVEL"), AuditEvent.INVENTORY);
            expectText(AuditEvent.moduleOf("SALE_COMPLETED"), AuditEvent.POS);
            expectText(AuditEvent.moduleOf("LOGS_EXPORTED"), AuditEvent.SYSTEM);
            expectText(AuditEvent.moduleOf("SOMETHING_NEW"), AuditEvent.OTHER);
            expectText(AuditEvent.parse("   ").action(), "UNKNOWN");
            expectText(AuditEvent.parse(null).module(), AuditEvent.OTHER);
            expectText(AuditEvent.readable("PRICE_CHANGED"), "Price changed");
        });

        check("Filter: From after To is refused; blanks mean no limit", () -> {
            expectThrows(() -> new ActivityFilter(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 29),
                    null, null, null), IllegalArgumentException.class);
            ActivityFilter f = new ActivityFilter(null, null, "  ", "", " ballpen ");
            expectTrue(f.userId() == null && f.module() == null, "blanks become null");
            expectText(f.keyword(), "ballpen");
        });

        check("Logger: saves the event with user, terminal and time", () -> {
            MemoryLog log = new MemoryLog();
            new ActivityLogger(log, "T02", Clock.fixed(NOW, ZoneOffset.UTC))
                    .log("PRICE_CHANGED target=P-0001 retail=250.00>260.00", "NSC-00001");
            expectTrue(log.saved.size() == 1, "one row");
            ActivityEntry e = log.saved.get(0);
            expectText(e.terminalId(), "T02");
            expectText(e.userId(), "NSC-00001");
            expectTrue(e.createdAt().equals(NOW), "time from the clock");
            expectText(e.newValue(), "Retail 260.00");
        });

        check("Failed sign-ins say why, and are told apart from denied ones", () -> {
            expectText(AuditEvent.readable("LOGIN_FAILED_UNKNOWN_USER"), "Invalid username");
            expectText(AuditEvent.readable("LOGIN_FAILED_WRONG_PASSWORD"), "Invalid password");
            expectText(AuditEvent.readable("LOGIN_BLOCKED_LOCKED"), "Account locked");
            expectText(AuditEvent.readable("LOGIN_FAILED"), "Sign-in failed");   // older entries still read well
            expectTrue(AuditEvent.isLoginProblem("LOGIN_BLOCKED_INACTIVE") && !AuditEvent.isLoginProblem("LOGIN_SUCCESS"),
                    "login problems recognised");
            expectText(AuditEvent.parse("LOGIN_FAILED_WRONG_PASSWORD").module(), AuditEvent.LOGIN);
        });

        check("Details: one professional sentence per action, built from the raw words", () -> {
            expectText(describe("LOGIN_SUCCESS", null, null, null, null), "Sign-in successful.");
            expectText(describe("LOGIN_FAILED_UNKNOWN_USER", "jdoe", null, null, null),
                    "Sign-in failed: the username is not registered.");
            expectText(describe("LOGIN_FAILED_WRONG_PASSWORD", null, null, null, null), "Sign-in failed: incorrect password.");
            expectText(describe("LOGIN_BLOCKED_INACTIVE", null, null, null, null),
                    "Sign-in denied: the account is deactivated.");
            // An older entry that saved an informal explanation as its details still gets the standard sentence.
            expectText(describe("LOGIN_FAILED_UNKNOWN_USER", "jdoe", "No account has this username (a typo).", null, null),
                    "Sign-in failed: the username is not registered.");
            expectText(describe("PRICE_CHANGED", "P-0001", null, "Retail 250.00", "Retail 260.00"),
                    "Price updated: Retail 250.00 → Retail 260.00.");
            expectText(describe("STOCK_IN", "P-0001", "+20", null, null), "Stock received: quantity 20.");
            expectText(describe("STOCK_OUT", "P-0001", "-2 DAMAGE", null, null),
                    "Stock removed: quantity 2 (reason: damage).");
            expectText(describe("STOCK_ADJUSTED", "P-0001", "to 10.5 COUNT_CORRECTION", null, null),
                    "Stock count adjusted: set to 10.5 (reason: count correction).");
            expectText(describe("REORDER_LEVEL", "P-0001", "5", null, null), "Reorder level set to 5.");
            expectText(describe("SALE_COMPLETED", "T01-2026-000143", null, null, null), "Sale completed.");
            expectText(describe("LOGS_EXPORTED", "ActivityLog", "1 rows", null, null), "Activity log exported: 1 entry.");
            expectText(describe("LOGS_EXPORTED", "ActivityLog", "2,500 rows", null, null),
                    "Activity log exported: 2,500 entries.");
            expectText(describe("SOMETHING_NEW", null, "extra words", null, null), "Something new: extra words.");
            expectText(describe("SOMETHING_NEW", null, null, null, null), "Something new.");
            // Every sentence follows the convention: starts with a capital letter, ends with a period.
            for (String action : List.of("LOGOUT", "PASSWORD_CHANGED", "PASSWORD_CHANGE_FAILED", "PASSWORD_RESET",
                    "USER_CREATED", "USER_DEACTIVATED", "PRODUCT_CREATED", "PRODUCT_DEACTIVATED", "CATEGORY_RENAMED",
                    "SUPPLIER_UPDATED", "STOCK_IN", "STOCK_OUT", "STOCK_ADJUSTED", "REORDER_LEVEL", "LOGS_EXPORTED")) {
                String s = describe(action, null, null, null, null);
                expectTrue(Character.isUpperCase(s.charAt(0)) && s.endsWith(".") && !s.contains("_"),
                        action + " -> " + s);
            }
        });

        check("Logger: an unknown username is kept as the target, not as a user", () -> {
            MemoryLog log = new MemoryLog();
            ActivityLogger logger = new ActivityLogger(log, "T01", Clock.fixed(NOW, ZoneOffset.UTC));
            logger.log("LOGIN_FAILED_UNKNOWN_USER", "jdelacruz ");
            logger.log("LOGIN_FAILED_UNKNOWN_USER", "");
            logger.log("LOGIN_FAILED_WRONG_PASSWORD", "NSC-00002");
            ActivityEntry unknown = log.saved.get(0);
            expectTrue(unknown.userId() == null, "no user ID");
            expectText(unknown.target(), "jdelacruz");
            expectTrue(unknown.details() == null, "raw details stay empty; the sentence is made when shown");
            expectText(log.saved.get(1).target(), "(blank)");
            ActivityEntry wrong = log.saved.get(2);
            expectText(wrong.userId(), "NSC-00002");
            expectTrue(wrong.target() == null, "a wrong password has the real user, no target");
        });

        check("Logger: a database failure never stops the action being logged", () -> {
            MemoryLog log = new MemoryLog();
            log.broken = true;
            new ActivityLogger(log, "T01").log("SALE_COMPLETED T01-2026-000001", "NSC-00002");   // must not throw
            expectTrue(log.saved.isEmpty(), "nothing saved");
        });

        check("Service: refused without permission", () -> {
            ActivityLogService service = new ActivityLogService(new MemoryLog(), session -> {
                throw new SystemException("You do not have permission to view system logs.");
            }, null);
            expectThrows(() -> service.find("s", ActivityFilter.all()), SystemException.class);
            expectThrows(() -> service.userIds("s"), SystemException.class);
        });

        check("Service: a database failure becomes a readable message", () -> {
            MemoryLog log = new MemoryLog();
            log.broken = true;
            ActivityLogService service = new ActivityLogService(log, session -> ADMIN, null);
            try {
                service.find("s", ActivityFilter.all());
                throw new AssertionError("expected SystemException");
            } catch (SystemException e) {
                expectText(e.getMessage(), "The activity log could not be loaded. Try again.");
            }
        });

        check("Export: CSV opens in Excel, quotes commas, blocks formulas, and is itself logged", () -> {
            MemoryLog log = new MemoryLog();
            List<String> audited = new ArrayList<>();
            ActivityLogService service = new ActivityLogService(log, session -> ADMIN,
                    (event, userId) -> audited.add(event + " by " + userId));
            List<ActivityEntry> rows = List.of(
                    new ActivityEntry(1, NOW, "NSC-00001", "T01", "Products", "PRICE_CHANGED", "P-0001",
                            null, "Retail 250.00, Wholesale none", "Retail 260.00, Wholesale 240.00"),
                    new ActivityEntry(2, NOW, "=cmd", "T01", "Login", "LOGIN_FAILED", null, null, null, null));
            Path file = Files.createTempFile("activity-check", ".csv");
            try {
                int n = service.exportCsv("s", rows, file, id -> id.equals("NSC-00001") ? "Test Admin" : null);
                expectTrue(n == 2, "two rows written");
                String csv = Files.readString(file, StandardCharsets.UTF_8);
                expectTrue(csv.startsWith("﻿Date / Time,User ID,User,"), "BOM and header: " + csv);
                expectTrue(csv.contains("2026-10-29 10:42:00,NSC-00001,Test Admin,T01,Products,PRICE_CHANGED,P-0001,"
                        + "\"Retail 250.00, Wholesale none\",\"Retail 260.00, Wholesale 240.00\","), "row 1: " + csv);
                expectTrue(csv.contains(",'=cmd,"), "formula blocked: " + csv);
                expectTrue(csv.contains(",T01,Sign-in,LOGIN_FAILED,"), "module worded as on screen: " + csv);
                expectTrue(audited.size() == 1 && audited.get(0).startsWith("LOGS_EXPORTED target=ActivityLog 2 rows"),
                        "export logged: " + audited);
            } finally {
                Files.deleteIfExists(file);
            }
        });

        check("Search: the words on screen find the entry, although the log stores codes", () -> {
            expectTrue(ActivityDescription.actionsMatching("sign-in").contains("LOGIN_SUCCESS"), "action wording");
            expectTrue(ActivityDescription.actionsMatching("Invalid password").contains("LOGIN_FAILED_WRONG_PASSWORD"),
                    "failed sign-in wording");
            expectTrue(ActivityDescription.actionsMatching("stock removed").contains("STOCK_OUT"), "built sentence");
            expectTrue(ActivityDescription.actionsMatching("inventory log").contains("LOGS_EXPORTED"), "export sentence");
            expectTrue(ActivityDescription.actionsMatching("no-such-words-zzz").isEmpty(), "nothing matches");
            new MySqlActivityLogRepository().find(new ActivityFilter(null, null, null, null, "sign-in"), 5);
        });

        check("Module: the Login module is worded Sign-in on screen, and stored unchanged", () -> {
            expectText(AuditEvent.moduleLabel(AuditEvent.LOGIN), "Sign-in");
            expectText(AuditEvent.moduleLabel(AuditEvent.PRODUCTS), "Products");
            expectText(AuditEvent.parse("LOGIN_SUCCESS").module(), "Login");
        });

        check("Database: an entry is saved and found again by keyword, user, module and date", () -> {
            MySqlActivityLogRepository db = new MySqlActivityLogRepository();
            String marker = "CHK-" + UUID.randomUUID().toString().substring(0, 8);
            Instant at = Instant.now();
            db.append(ActivityEntry.of(AuditEvent.parse("LOGS_EXPORTED target=" + marker + " self-test"),
                    "NSC-00001", "TEST", at));
            LocalDate today = shared.PhTime.of(at).toLocalDate();
            List<ActivityEntry> found = db.find(new ActivityFilter(today, today, "NSC-00001", AuditEvent.SYSTEM, marker), 10);
            expectTrue(found.size() == 1, "found once: " + found);
            ActivityEntry e = found.get(0);
            expectText(e.target(), marker);
            expectText(e.terminalId(), "TEST");
            expectText(e.details(), "self-test");
            expectTrue(Math.abs(e.createdAt().toEpochMilli() - at.toEpochMilli()) < 1000, "time kept");
            expectTrue(db.find(new ActivityFilter(today.minusDays(3), today.minusDays(2), null, null, marker), 10)
                    .isEmpty(), "other days exclude it");
            expectTrue(db.find(new ActivityFilter(null, null, null, null, "%"), 1).size() <= 1, "% is literal");
            expectTrue(db.userIds().contains("NSC-00001"), "user listed");
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** The Details sentence for an entry with this action and raw values. */
    static String describe(String action, String target, String details, String oldValue, String newValue) {
        return ActivityDescription.describe(new ActivityEntry(0, NOW, "NSC-00001", "T01",
                AuditEvent.moduleOf(action), action, target, details, oldValue, newValue));
    }

    /** An in-memory activity log; {@code broken} makes every call fail like a database that is down. */
    static final class MemoryLog implements ActivityLogRepository {
        final List<ActivityEntry> saved = new ArrayList<>();
        boolean broken;

        @Override
        public void append(ActivityEntry entry) {
            if (broken) {
                throw new RuntimeException("Communications link failure");
            }
            saved.add(entry);
        }

        @Override
        public List<ActivityEntry> find(ActivityFilter filter, int limit) {
            if (broken) {
                throw new RuntimeException("Communications link failure");
            }
            return List.copyOf(saved);
        }

        @Override
        public List<String> userIds() {
            if (broken) {
                throw new RuntimeException("Communications link failure");
            }
            return saved.stream().map(ActivityEntry::userId).distinct().toList();
        }

        @Override
        public Optional<Instant> oldest() {
            return saved.stream().map(ActivityEntry::createdAt).min(Instant::compareTo);
        }

        @Override
        public long countOlderThan(Instant time) {
            return saved.stream().filter(e -> e.createdAt().isBefore(time)).count();
        }
    }

    /* ------------------------------ helpers ------------------------------ */

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
