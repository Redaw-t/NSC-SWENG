package system.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One audit event as the modules send it through {@code auth.contract.AuditLogger}, split into
 * the columns of the activity log. Modules send one line of text, for example:
 *
 *   LOGIN_SUCCESS
 *   PRODUCT_UPDATED target=P-0001
 *   PRICE_CHANGED target=P-0001 retail=250.00>260.00 wholesale=none>240.00
 *   STOCK_ADJUSTED P-0001 to 10 COUNT_CORRECTION
 *   SALE_COMPLETED T01-2026-000143
 *
 * The first word is the action. {@code target=X} names what it was done to; without it, the
 * second word is the target. Each {@code name=old>new} word is a before/after pair and goes to
 * the old / new value columns. Whatever is left is kept as the raw details; the sentence the
 * screen shows is made from them by {@link ActivityDescription}.
 *
 * @param module   which part of the system the action belongs to (one of {@link #MODULES})
 * @param action   e.g. LOGIN_SUCCESS
 * @param target   user ID, product ID, invoice number ...; null if none
 * @param details  the rest of the event; null if nothing is left
 * @param oldValue "Retail 250.00, Wholesale none"; null if the event has no before/after pairs
 * @param newValue "Retail 260.00, Wholesale 240.00"; null if the event has no before/after pairs
 */
public record AuditEvent(String module, String action, String target, String details,
                         String oldValue, String newValue) {

    public static final String LOGIN = "Login";
    public static final String USERS = "Users";
    public static final String PRODUCTS = "Products";
    public static final String INVENTORY = "Inventory";
    public static final String POS = "POS";
    public static final String SYSTEM = "System";
    public static final String OTHER = "Other";

    /** Every module name, in the order the filter lists them. */
    public static final List<String> MODULES = List.of(LOGIN, USERS, PRODUCTS, INVENTORY, POS, SYSTEM, OTHER);

    /** A login with a username no account has; the "user" sent with it is the name that was typed. */
    public static final String UNKNOWN_USER = "LOGIN_FAILED_UNKNOWN_USER";

    /** Action column wording for the failed / denied sign-ins (older logs used plain LOGIN_FAILED). */
    private static final Map<String, String> LOGIN_PROBLEMS = Map.of(
            UNKNOWN_USER, "Invalid username",
            "LOGIN_FAILED_WRONG_PASSWORD", "Invalid password",
            "LOGIN_FAILED", "Sign-in failed",
            "LOGIN_BLOCKED_INACTIVE", "Account deactivated",
            "LOGIN_BLOCKED_LOCKED", "Account locked");

    /** Action column wording that "replace _ with spaces" would get wrong or word differently. */
    private static final Map<String, String> LABELS = Map.of(
            "LOGIN_SUCCESS", "Sign-in",
            "LOGOUT", "Sign-out",
            "LOGS_EXPORTED", "Log export",
            "REORDER_LEVEL", "Reorder level changed",
            "STOCK_IN", "Stock in",
            "STOCK_OUT", "Stock out");

    public AuditEvent {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(action, "action");
    }

    /** Splits one event line. A blank line becomes the action UNKNOWN. */
    public static AuditEvent parse(String event) {
        String[] words = event == null ? new String[0] : event.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return new AuditEvent(OTHER, "UNKNOWN", null, null, null, null);
        }
        String action = words[0];
        String target = null;
        List<String> rest = new ArrayList<>();
        List<String> olds = new ArrayList<>();
        List<String> news = new ArrayList<>();
        boolean explicitTarget = false;
        for (int i = 1; i < words.length; i++) {
            String word = words[i];
            int eq = word.indexOf('=');
            int arrow = word.indexOf('>');
            if (word.startsWith("target=") && word.length() > "target=".length()) {
                target = word.substring("target=".length());
                explicitTarget = true;
            } else if (eq > 0 && arrow > eq) {
                String name = label(word.substring(0, eq));
                olds.add(name + " " + word.substring(eq + 1, arrow));
                news.add(name + " " + word.substring(arrow + 1));
            } else {
                rest.add(word);
            }
        }
        if (!explicitTarget && !rest.isEmpty()) {
            target = rest.remove(0);
        }
        return new AuditEvent(moduleOf(action), action, target,
                rest.isEmpty() ? null : String.join(" ", rest),
                olds.isEmpty() ? null : String.join(", ", olds),
                news.isEmpty() ? null : String.join(", ", news));
    }

    /** The module an action belongs to, from its name. */
    public static String moduleOf(String action) {
        String a = action.toUpperCase(Locale.ROOT);
        if (a.equals("PASSWORD_RESET") || a.startsWith("USER_")) {
            return USERS;   // done by an admin to someone else's account
        }
        if (a.startsWith("LOGIN") || a.equals("LOGOUT") || a.startsWith("PASSWORD_")) {
            return LOGIN;
        }
        if (a.startsWith("PRODUCT_") || a.startsWith("PRICE_") || a.startsWith("CATEGORY_")
                || a.startsWith("SUPPLIER_")) {
            return PRODUCTS;
        }
        if (a.startsWith("STOCK_") || a.startsWith("REORDER_")) {
            return INVENTORY;
        }
        if (a.startsWith("SALE_")) {
            return POS;
        }
        if (a.startsWith("LOGS_") || a.startsWith("BACKUP_") || a.startsWith("RESTORE_") || a.startsWith("SYNC_")) {
            return SYSTEM;
        }
        return OTHER;
    }

    /** How a module is named on screens and in exports: the same "Sign-in" wording as the Action column. */
    public static String moduleLabel(String module) {
        return LOGIN.equals(module) ? "Sign-in" : module;
    }

    /** True for a failed or blocked login attempt. */
    public static boolean isLoginProblem(String action) {
        return action != null && LOGIN_PROBLEMS.containsKey(action);
    }

    /** "PRICE_CHANGED" as "Price changed" (failed sign-ins say why), for screens. */
    public static String readable(String action) {
        if (action == null || action.isEmpty()) {
            return "";
        }
        if (LOGIN_PROBLEMS.containsKey(action)) {
            return LOGIN_PROBLEMS.get(action);
        }
        if (LABELS.containsKey(action)) {
            return LABELS.get(action);
        }
        String words = action.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** "retail" as "Retail". */
    private static String label(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
