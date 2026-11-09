package system.model;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Details wording of the activity log: one professional sentence per entry, the same in the
 * table, the View pop-up and the CSV export.
 *
 * <h2>Convention for every Details message</h2>
 * <ol>
 *   <li>One complete sentence in sentence case, ending with a period.</li>
 *   <li>Pattern: <b>what happened</b>, then a colon and the <b>specifics</b> when there are any:
 *       "Stock removed: quantity 2 (reason: damage)."</li>
 *   <li>States facts in the past tense. No guesses about why, no informal words.</li>
 *   <li>Business words, never codes: "sign-in", "account", "count correction", not
 *       LOGIN_FAILED or COUNT_CORRECTION.</li>
 *   <li>Who did it, what it was done to and where are in their own columns (User, Target,
 *       Terminal); the sentence adds only what those columns cannot say.</li>
 *   <li>Unsuccessful attempts start with "Sign-in failed:" (wrong details) or "Sign-in denied:"
 *       (correct details, but the account may not sign in).</li>
 * </ol>
 *
 * The sentence is built when the entry is shown, from the action and the raw words saved with it,
 * so entries saved before a wording change read the same as new ones. A new action gets its
 * sentence by adding it to {@link #FIXED} (or a case in {@link #describe}).
 */
public final class ActivityDescription {

    /** Actions whose sentence never changes. */
    private static final Map<String, String> FIXED = Map.ofEntries(
            // Login
            Map.entry("LOGIN_SUCCESS", "Sign-in successful."),
            Map.entry("LOGOUT", "Signed out."),
            Map.entry(AuditEvent.UNKNOWN_USER, "Sign-in failed: the username is not registered."),
            Map.entry("LOGIN_FAILED_WRONG_PASSWORD", "Sign-in failed: incorrect password."),
            Map.entry("LOGIN_FAILED", "Sign-in failed: unregistered username or incorrect password."),
            Map.entry("LOGIN_BLOCKED_INACTIVE", "Sign-in denied: the account is deactivated."),
            Map.entry("LOGIN_BLOCKED_LOCKED", "Sign-in denied: the account is locked."),
            Map.entry("PASSWORD_CHANGED", "Password changed by the account owner."),
            Map.entry("PASSWORD_CHANGE_FAILED", "Password change rejected: the current password entered was incorrect."),
            // Users
            Map.entry("PASSWORD_RESET", "Password reset by an administrator."),
            Map.entry("USER_CREATED", "User account created."),
            Map.entry("USER_UPDATED", "User account details updated."),
            Map.entry("USER_ACTIVATED", "User account activated."),
            Map.entry("USER_DEACTIVATED", "User account deactivated."),
            // Products
            Map.entry("PRODUCT_CREATED", "Product added to the catalog."),
            Map.entry("PRODUCT_UPDATED", "Product details updated."),
            Map.entry("PRODUCT_ACTIVATED", "Product activated."),
            Map.entry("PRODUCT_DEACTIVATED", "Product deactivated: it can no longer be sold."),
            Map.entry("CATEGORY_CREATED", "Category created."),
            Map.entry("CATEGORY_RENAMED", "Category renamed."),
            Map.entry("CATEGORY_ACTIVATED", "Category activated."),
            Map.entry("CATEGORY_DEACTIVATED", "Category deactivated."),
            Map.entry("SUPPLIER_CREATED", "Supplier added."),
            Map.entry("SUPPLIER_UPDATED", "Supplier details updated."),
            Map.entry("SUPPLIER_ACTIVATED", "Supplier activated."),
            Map.entry("SUPPLIER_DEACTIVATED", "Supplier deactivated."),
            // POS
            Map.entry("SALE_COMPLETED", "Sale completed."),
            Map.entry("SALE_ITEM_VOIDED", "Item(s) voided from a completed sale and refunded."),
            Map.entry("SALE_VOIDED", "Completed sale voided (Void Transaction) and refunded."),
            // System (the file or record is in the Target column)
            Map.entry("BACKUP_CREATED", "Database backup created."),
            Map.entry("BACKUP_FAILED", "Database backup failed."),
            Map.entry("RESTORE_COMPLETED", "Database restored from a backup."),
            Map.entry("RESTORE_FAILED", "Database restore failed: the previous data was put back."),
            Map.entry("RESTORE_FAILED_UNRECOVERED", "Database restore failed: the previous data could not be put back."),
            Map.entry("BACKUP_FOLDER_CHANGED", "Backup folder changed."),
            Map.entry("SYNC_RETRY_REQUESTED", "Sync retry requested."),
            Map.entry("STORE_DETAILS_REGISTERED", "Store details registered in Installation Setup."),
            Map.entry("STORE_DETAILS_CHANGED", "Store details on receipts changed."),
            Map.entry("MAIN_POS_ADDRESS_CHANGED", "Main POS address changed in Settings."),
            Map.entry("LOGS_CLEARED", "Old log entries cleared in Settings."));

    /** How the sentences built in {@link #describe} start, so a search for those words finds them. */
    private static final Map<String, String> OPENINGS = Map.of(
            "PRICE_CHANGED", "Price updated",
            "STOCK_IN", "Stock received",
            "STOCK_OUT", "Stock removed",
            "STOCK_ADJUSTED", "Stock count adjusted",
            "REORDER_LEVEL", "Reorder level set",
            "LOGS_EXPORTED", "Activity log exported, Inventory log exported, Sync log exported");

    private static final Pattern SIGNED_QTY = Pattern.compile("^[+-]?([0-9.,]+)(?:\\s+(\\S+))?$");
    private static final Pattern SET_TO = Pattern.compile("^(?:to\\s+)?([0-9.,]+)(?:\\s+(\\S+))?$");
    private static final Pattern ROWS = Pattern.compile("^([0-9,]+)\\s+rows?$");

    private ActivityDescription() { }

    /** The Details sentence for {@code e}. */
    public static String describe(ActivityEntry e) {
        String action = e.action();
        String raw = e.details() == null ? "" : e.details().trim();
        String fixed = FIXED.get(action);
        if (fixed != null) {
            return fixed;
        }
        switch (action) {
            case "PRICE_CHANGED" -> {
                return e.oldValue() == null && e.newValue() == null ? "Price updated."
                        : "Price updated: " + orNone(e.oldValue()) + " → " + orNone(e.newValue()) + ".";
            }
            case "STOCK_IN" -> {
                Matcher m = SIGNED_QTY.matcher(raw);
                return m.matches() ? "Stock received: quantity " + m.group(1) + "." : "Stock received.";
            }
            case "STOCK_OUT" -> {
                Matcher m = SIGNED_QTY.matcher(raw);
                return m.matches() ? "Stock removed: quantity " + m.group(1) + reason(m.group(2)) + "." : "Stock removed.";
            }
            case "STOCK_ADJUSTED" -> {
                Matcher m = SET_TO.matcher(raw);
                return m.matches() ? "Stock count adjusted: set to " + m.group(1) + reason(m.group(2)) + "."
                        : "Stock count adjusted.";
            }
            case "REORDER_LEVEL" -> {
                Matcher m = SET_TO.matcher(raw);
                return m.matches() ? "Reorder level set to " + m.group(1) + "." : "Reorder level changed.";
            }
            case "LOGS_EXPORTED" -> {
                String log = "InventoryLog".equals(e.target()) ? "Inventory log"
                        : "SyncLog".equals(e.target()) ? "Sync log" : "Activity log";
                Matcher m = ROWS.matcher(raw);
                if (!m.matches()) {
                    return log + " exported.";
                }
                return log + " exported: " + m.group(1) + (m.group(1).equals("1") ? " entry." : " entries.");
            }
            default -> {
                // An action without its own sentence yet: its name, then whatever was sent with it.
                String what = AuditEvent.readable(action);
                return raw.isEmpty() ? what + "." : what + ": " + sentenceEnd(raw);
            }
        }
    }

    /**
     * The actions whose wording on screen contains {@code keyword}. The log stores action codes, and
     * the sentence is only made when an entry is shown, so a search for what the admin can see
     * ("Sign-in", "Stock removed") has to be turned back into codes.
     */
    public static Set<String> actionsMatching(String keyword) {
        String wanted = keyword.toLowerCase(Locale.ROOT);
        Set<String> out = new TreeSet<>();
        FIXED.forEach((action, sentence) -> {
            if (wording(action, sentence).contains(wanted)) {
                out.add(action);
            }
        });
        OPENINGS.forEach((action, opening) -> {
            if (wording(action, opening).contains(wanted)) {
                out.add(action);
            }
        });
        return out;
    }

    private static String wording(String action, String sentence) {
        return (AuditEvent.readable(action) + " " + sentence).toLowerCase(Locale.ROOT);
    }

    /** " (reason: count correction)" from COUNT_CORRECTION; empty if there is no reason. */
    private static String reason(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return " (reason: " + code.replace('_', ' ').toLowerCase(Locale.ROOT) + ")";
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String sentenceEnd(String text) {
        return text.endsWith(".") ? text : text + ".";
    }
}
