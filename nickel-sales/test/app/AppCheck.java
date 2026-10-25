package app;

import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import auth.permission.AuthorizationService;
import stubs.StubInventory;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SELF-TEST for POS Phase 3: the app's settings, wiring and home screen
 * menu. Checks that logging in as each role shows only the buttons that
 * role may use (no window is opened; the menu logic is tested directly).
 *
 * Run after any run-demo build, from nickel-sales/ (JavaFX jars on the
 * classpath because the modules' screen classes are loaded):
 *   java -cp "out;lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar;lib\javafx\*" app.AppCheck
 * (macOS/Linux: ':' instead of ';'). Exit code 1 on failure.
 */
public class AppCheck {

    static int passed = 0;
    static int failed = 0;

    static final AuthorizationService authz = new AuthorizationService();

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" NICKEL SALES CENTER - POS Phase 3 checks (app and home screen)");
        System.out.println("=================================================================");

        check("Settings: defaults, terminal ID and idle timeout from the environment", () -> {
            AppConfig defaults = AppConfig.from(Map.of());
            expectText(defaults.terminalId(), "T01");
            expectTrue(defaults.idleTimeout().equals(Duration.ofMinutes(30)), "30 minutes by default");
            AppConfig set = AppConfig.from(Map.of("NSC_TERMINAL_ID", " T02 ", "NSC_IDLE_TIMEOUT_MINUTES", "5"));
            expectText(set.terminalId(), "T02");
            expectTrue(set.idleTimeout().equals(Duration.ofMinutes(5)), "5 minutes");
            expectTrue(AppConfig.from(Map.of("NSC_IDLE_TIMEOUT_MINUTES", "soon")).idleTimeout()
                    .equals(Duration.ofMinutes(30)), "bad timeout falls back to 30");
            expectTrue(AppConfig.from(Map.of("NSC_IDLE_TIMEOUT_MINUTES", "0")).idleTimeout()
                    .equals(Duration.ofMinutes(30)), "0 falls back to 30");
            expectThrows(() -> AppConfig.from(Map.of("NSC_TERMINAL_ID", "TERMINAL 1")), IllegalArgumentException.class);
        });

        check("Receipt settings: store details and paper width", () -> {
            AppConfig defaults = AppConfig.from(Map.of());
            expectText(defaults.store().name(), "NICKEL SALES CENTER");
            expectText(defaults.store().address(), "");
            expectTrue(defaults.receiptWidth() == 42, "42 characters (80 mm) by default");
            AppConfig set = AppConfig.from(Map.of("NSC_STORE_NAME", "Nickel Sales Center - Branch 2",
                    "NSC_STORE_ADDRESS", "123 Rizal Ave.", "NSC_STORE_TIN", "000-123", "NSC_STORE_PHONE", "8123",
                    "NSC_RECEIPT_WIDTH", "32"));
            expectText(set.store().name() + "|" + set.store().address() + "|" + set.store().tin() + "|"
                    + set.store().phone(), "Nickel Sales Center - Branch 2|123 Rizal Ave.|000-123|8123");
            expectTrue(set.receiptWidth() == 32, "58 mm paper");
            expectTrue(AppConfig.from(Map.of("NSC_RECEIPT_WIDTH", "80")).receiptWidth() == 42, "out of range -> 42");
        });

        check("Every module has a unique title, a description and a permission", () -> {
            List<AppModule> all = Modules.all();
            Set<String> titles = new HashSet<>();
            for (AppModule m : all) {
                expectTrue(m.title() != null && !m.title().isBlank(), "title");
                expectTrue(m.description() != null && !m.description().isBlank(), m.title() + " description");
                expectTrue(m.permission() != null, m.title() + " permission");
                expectTrue(titles.add(m.title()), "duplicate title " + m.title());
            }
            expectTrue(all.size() <= 9, "at most 9, so number keys 1-9 reach them all");
        });

        check("Cashier (JDelaCruz) sees only Point of Sale", () ->
                expectMenu(user("NSC-00002", UserStatus.ACTIVE, UserRole.CASHIER), "Point of Sale"));

        check("Inventory staff (MSantaAna) sees Products & inventory and Reports, not POS or users", () ->
                expectMenu(user("NSC-00003", UserStatus.ACTIVE, UserRole.INVENTORY_STAFF),
                        "Products & inventory", "Reports"));

        check("Admin sees every button; an admin who is also cashier and inventory (JCubillan) too", () -> {
            String[] everything = {"Point of Sale", "Products & inventory", "Reports", "Manage users", "System & sync"};
            expectMenu(user("NSC-09999", UserStatus.ACTIVE, UserRole.ADMIN), everything);
            expectMenu(user("NSC-00001", UserStatus.ACTIVE, UserRole.ADMIN, UserRole.CASHIER, UserRole.INVENTORY_STAFF),
                    everything);
        });

        check("Inactive (RSantos), locked (KRodriguez) and no-role users see nothing", () -> {
            expectMenu(user("NSC-00004", UserStatus.INACTIVE, UserRole.CASHIER));
            expectMenu(user("NSC-00005", UserStatus.LOCKED, UserRole.CASHIER));
            expectMenu(user("NSC-08888", UserStatus.ACTIVE));
            expectMenu(null);
        });

        check("Only Manage users needs MANAGE_USERS, so only admins get it", () -> {
            for (UserRole role : List.of(UserRole.CASHIER, UserRole.INVENTORY_STAFF)) {
                expectTrue(Modules.visibleFor(user("X", UserStatus.ACTIVE, role), authz, Modules.all()).stream()
                        .noneMatch(m -> m.title().equals("Manage users")), role + " can't manage users");
            }
        });

        check("Shared services: POS only when products are connected; modules can add services", () -> {
            AppConfig config = AppConfig.from(Map.of("NSC_TERMINAL_ID", "T07"));
            AppContext none = AppContext.create(config, null, null);
            expectTrue(!none.posAvailable() && none.salesService().isEmpty(), "no products -> no POS");
            expectTrue(none.saleExport() != null && none.saleHistory() != null, "Sync and Reports always get sales");

            StubInventory stock = new StubInventory();
            AppContext withProducts = AppContext.create(config, stock, stock);
            expectTrue(withProducts.posAvailable(), "POS available");
            withProducts.salesService().orElseThrow().startSale("NSC-00002");
            expectText(withProducts.salesService().orElseThrow().currentSale().orElseThrow().getTerminalId(), "T07");

            withProducts.register(StringBuilder.class, new StringBuilder("inventory service"));
            expectText(withProducts.find(StringBuilder.class).orElseThrow().toString(), "inventory service");
            expectTrue(withProducts.find(Integer.class).isEmpty(), "unknown service is empty");
            expectThrows(() -> withProducts.register(StringBuilder.class, new StringBuilder()), IllegalStateException.class);
        });

        System.out.println("-----------------------------------------------------------------");
        System.out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------- helpers ------------------------------- */

    static User user(String id, UserStatus status, UserRole... roles) {
        Set<UserRole> set = roles.length == 0 ? EnumSet.noneOf(UserRole.class) : EnumSet.of(roles[0], roles);
        return new User(id, id, "$2a$10$hash", "Test", "User", set, status);
    }

    /** The home screen buttons for {@code user} are exactly {@code titles}, in this order. */
    static void expectMenu(User user, String... titles) {
        List<String> shown = Modules.visibleFor(user, authz, Modules.all()).stream().map(AppModule::title).toList();
        if (!shown.equals(List.of(titles))) {
            throw new AssertionError("expected " + List.of(titles) + " but was " + shown);
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
