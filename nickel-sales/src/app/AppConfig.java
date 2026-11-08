package app;

import pos.repository.InvoiceNumberGenerator;
import pos.receipt.Receipt;
import pos.receipt.StoreInfo;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Settings for this terminal, read once at start-up from the settings file the
 * installer writes, or environment variables of the same names (see
 * shared.Settings; the NSC_DB_* database settings are read the same way):
 *
 * | Setting                  | Meaning                                      | Default             |
 * | NSC_TERMINAL_ID          | this terminal's code, used in invoice nos.     | T01                 |
 * | NSC_IDLE_TIMEOUT_MINUTES | minutes without activity before logout        | 30                  |
 * | NSC_STORE_NAME           | store name at the top of receipts             | NICKEL SALES CENTER |
 * | NSC_STORE_ADDRESS        | address on receipts                           | the store's address |
 * | NSC_STORE_PROPRIETOR     | owner, printed "NAME - PROPRIETOR"            | the store's owner   |
 * | NSC_STORE_TIN            | VAT-REG TIN on receipts                       | the store's TIN     |
 * | NSC_STORE_PHONE          | phone on receipts (left off if not set)       |                     |
 * | NSC_RECEIPT_WIDTH        | characters per receipt line: 32 for 58 mm     | 32                  |
 * |                          | paper, 42-48 for 80 mm (32-48)                |                     |
 *
 * The address, proprietor and TIN default to Nickel Sales Center's own details
 * (pos.receipt.StoreInfo.NICKEL_*); set a variable to override it, or to a blank
 * value to leave that line off the receipt.
 *
 * Give each terminal its own ID (T01, T02, ...) so their invoice numbers
 * never clash: NSC_TERMINAL_ID=T02 in the settings file, or on a developer's
 * Windows PC  setx NSC_TERMINAL_ID T02  (then open a new window).
 *
 * @param terminalId   1-8 letters, digits or dashes
 * @param idleTimeout  at least 1 minute
 * @param store        printed on receipts
 * @param receiptWidth characters per receipt line
 */
public record AppConfig(String terminalId, Duration idleTimeout, StoreInfo store, int receiptWidth) {

    public static final String DEFAULT_TERMINAL_ID = "T01";
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);

    public AppConfig {
        InvoiceNumberGenerator.checkTerminalId(terminalId);
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.toMinutes() < 1) {
            throw new IllegalArgumentException("idleTimeout must be at least 1 minute");
        }
        Objects.requireNonNull(store, "store");
        if (receiptWidth < Receipt.MIN_WIDTH || receiptWidth > Receipt.MAX_WIDTH) {
            throw new IllegalArgumentException("receiptWidth must be " + Receipt.MIN_WIDTH + "-" + Receipt.MAX_WIDTH);
        }
    }

    /** Terminal and idle timeout only; default store details and receipt width. */
    public AppConfig(String terminalId, Duration idleTimeout) {
        this(terminalId, idleTimeout, StoreInfo.named(StoreInfo.DEFAULT_NAME), Receipt.DEFAULT_WIDTH);
    }

    /** From this computer's settings file and environment variables (shared.Settings). */
    public static AppConfig fromSettings() {
        return from(shared.Settings.all());
    }

    /**
     * From a map of settings (shared.Settings.all(), or a test's own map).
     * A bad idle timeout or receipt width falls back to the default with a
     * warning; a bad terminal ID stops the app, because it would make wrong
     * invoice numbers.
     */
    public static AppConfig from(Map<String, String> env) {
        String terminal = env.getOrDefault("NSC_TERMINAL_ID", "").trim();
        if (terminal.isEmpty()) {
            terminal = DEFAULT_TERMINAL_ID;
        }
        long idleMinutes = number(env, "NSC_IDLE_TIMEOUT_MINUTES", DEFAULT_IDLE_TIMEOUT.toMinutes(), 1, Long.MAX_VALUE);
        int width = (int) number(env, "NSC_RECEIPT_WIDTH", Receipt.DEFAULT_WIDTH, Receipt.MIN_WIDTH, Receipt.MAX_WIDTH);
        StoreInfo store = new StoreInfo(env.get("NSC_STORE_NAME"),
                env.getOrDefault("NSC_STORE_ADDRESS", StoreInfo.NICKEL_ADDRESS),
                env.getOrDefault("NSC_STORE_PROPRIETOR", StoreInfo.NICKEL_PROPRIETOR),
                env.getOrDefault("NSC_STORE_TIN", StoreInfo.NICKEL_TIN),
                env.get("NSC_STORE_PHONE"));
        return new AppConfig(terminal, Duration.ofMinutes(idleMinutes), store, width);
    }

    /** A whole number setting between min and max, else the default (with a warning). */
    private static long number(Map<String, String> env, String name, long fallback, long min, long max) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long n = Long.parseLong(value.trim());
            if (n >= min && n <= max) {
                return n;
            }
            System.out.println("[CONFIG] Ignoring " + name + "=" + value + " (must be "
                    + (max == Long.MAX_VALUE ? min + " or more" : min + "-" + max) + ")");
        } catch (NumberFormatException e) {
            System.out.println("[CONFIG] Ignoring invalid " + name + "=" + value);
        }
        return fallback;
    }
}
