package app;

import pos.repository.InvoiceNumberGenerator;
import pos.receipt.Receipt;
import pos.receipt.StoreInfo;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Settings for this terminal, read once at start-up from environment
 * variables (like the NSC_DB_* database settings in shared.Database):
 *
 * | Variable                 | Meaning                                       | Default             |
 * | NSC_TERMINAL_ID          | this terminal's code, used in invoice nos.     | T01                 |
 * | NSC_IDLE_TIMEOUT_MINUTES | minutes without activity before logout        | 30                  |
 * | NSC_STORE_NAME           | store name at the top of receipts             | NICKEL SALES CENTER |
 * | NSC_STORE_ADDRESS        | address on receipts (left off if not set)     |                     |
 * | NSC_STORE_TIN            | TIN on receipts (left off if not set)         |                     |
 * | NSC_STORE_PHONE          | phone on receipts (left off if not set)       |                     |
 * | NSC_RECEIPT_WIDTH        | characters per receipt line: 42 for 80 mm     | 42                  |
 * |                          | paper, 32 for 58 mm (32-48)                   |                     |
 *
 * Give each terminal its own ID (T01, T02, ...) so their invoice numbers
 * never clash. On Windows:  setx NSC_TERMINAL_ID T02  (then open a new window).
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

    /** From this computer's environment variables. */
    public static AppConfig fromEnvironment() {
        return from(System.getenv());
    }

    /**
     * From a map of settings (the environment, or a test's own map).
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
        StoreInfo store = new StoreInfo(env.get("NSC_STORE_NAME"), env.get("NSC_STORE_ADDRESS"),
                env.get("NSC_STORE_TIN"), env.get("NSC_STORE_PHONE"));
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
