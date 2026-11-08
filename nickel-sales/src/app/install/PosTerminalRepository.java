package app.install;

import shared.AppConfigDatabase;
import sync.api.TerminalRegistrar;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * nickelsales_appconfig.PosTerminals (and the AppSettings it goes with): which POS terminals exist and
 * whether this installation is set up. Every change is one transaction. Database problems come out as
 * RuntimeException (cause: the SQLException), so screens can use shared.UiErrors.
 *
 * On the Main POS it is also the {@link TerminalRegistrar} behind the API: an Additional POS asking
 * for a terminal ID gets the next free one (T02, T03 ...), or the one it already has if it asks again.
 */
public class PosTerminalRepository implements TerminalRegistrar {

    /** The Main POS's terminal ID. */
    public static final String MAIN_ID = "T01";

    private static final Pattern TERMINAL_NUMBER = Pattern.compile("^T(\\d+)$");

    private final AppSettings settings = new AppSettings();

    /* ------------------------------- reading ------------------------------- */

    /** The terminal's row, if it is in the table. */
    public Optional<PosTerminal> find(String terminalId) {
        try (Connection c = AppConfigDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TerminalID, IPAddress FROM PosTerminals WHERE TerminalID = ?")) {
            ps.setString(1, terminalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new PosTerminal(rs.getString("TerminalID"), rs.getString("IPAddress")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read the POS terminal settings", e);
        }
    }

    /**
     * True while this installation is not set up: T01 has no IP address yet (on an Additional POS
     * T01 holds the Main POS's address), or it is not known which terminal this computer is.
     */
    public boolean needsSetup() {
        boolean mainKnown = find(MAIN_ID).map(PosTerminal::isSetUp).orElse(false);
        return !mainKnown || thisTerminalId().isEmpty();
    }

    /** Which terminal this computer is (T01, T02 ...), once set up. */
    public Optional<String> thisTerminalId() {
        return settings.get(AppSettings.THIS_TERMINAL_ID);
    }

    /** The saved developer API key (sent to the Main POS on API calls), once set up. */
    public Optional<String> apiKey() {
        return settings.get(AppSettings.API_KEY);
    }

    /** The Main POS's IP address (T01's row), once set up. */
    public Optional<String> mainPosAddress() {
        return find(MAIN_ID).map(PosTerminal::ipAddress);
    }

    /* ------------------------------- setup -------------------------------- */

    /** This computer is the Main POS: T01 gets its IP address, and the key is kept. */
    public void completeMainSetup(String ipAddress, String computerName, String apiKey) {
        inTransaction(c -> {
            upsertTerminal(c, MAIN_ID, ipAddress, computerName);
            AppSettings.put(c, AppSettings.THIS_TERMINAL_ID, MAIN_ID);
            AppSettings.put(c, AppSettings.API_KEY, apiKey);
            return null;
        });
    }

    /**
     * This computer is an Additional POS that the Main POS (at {@code mainIp}) gave {@code terminalId}:
     * T01 gets the Main POS's address, this terminal's own row its address, and the key is kept.
     */
    public void completeAdditionalSetup(String mainIp, String terminalId, String ipAddress, String computerName,
                                        String apiKey) {
        inTransaction(c -> {
            upsertTerminal(c, MAIN_ID, mainIp, null);
            upsertTerminal(c, terminalId, ipAddress, computerName);
            AppSettings.put(c, AppSettings.THIS_TERMINAL_ID, terminalId);
            AppSettings.put(c, AppSettings.API_KEY, apiKey);
            return null;
        });
    }

    /* --------------------- Main POS: handing out IDs --------------------- */

    /**
     * An Additional POS asks for its terminal ID. The same computer (by name, else by IP address)
     * asking again gets the ID it already has, with its address brought up to date; otherwise the next
     * free one (T02, T03 ...) is added. Two at once never get the same ID.
     */
    @Override
    public String register(String ipAddress, String computerName) {
        for (int attempt = 1; ; attempt++) {
            try {
                return inTransaction(c -> {
                    Optional<String> known = findAdditional(c, ipAddress, computerName);
                    if (known.isPresent()) {
                        upsertTerminal(c, known.get(), ipAddress, computerName);
                        return known.get();
                    }
                    String next = nextFreeId(c);
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO PosTerminals (TerminalID, IPAddress, ComputerName) VALUES (?, ?, ?)")) {
                        ps.setString(1, next);
                        ps.setString(2, ipAddress);
                        ps.setString(3, computerName);
                        ps.executeUpdate();
                    }
                    return next;
                });
            } catch (RuntimeException e) {
                // another terminal took the same ID a moment earlier: try once more with the next one
                if (attempt < 3 && e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    continue;
                }
                throw e;
            }
        }
    }

    /** An Additional POS already registered from this computer name (preferred) or IP address. */
    private static Optional<String> findAdditional(Connection c, String ipAddress, String computerName)
            throws SQLException {
        if (computerName != null && !computerName.isBlank()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT TerminalID FROM PosTerminals WHERE TerminalID <> ? AND ComputerName = ? FOR UPDATE")) {
                ps.setString(1, MAIN_ID);
                ps.setString(2, computerName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString(1));
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT TerminalID FROM PosTerminals WHERE TerminalID <> ? AND IPAddress = ? FOR UPDATE")) {
            ps.setString(1, MAIN_ID);
            ps.setString(2, ipAddress);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** One more than the highest T-number in use: T02 after T01, T03 after T02 ... (always at least T02). */
    private static String nextFreeId(Connection c) throws SQLException {
        int highest = 1;
        try (PreparedStatement ps = c.prepareStatement("SELECT TerminalID FROM PosTerminals FOR UPDATE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Matcher m = TERMINAL_NUMBER.matcher(rs.getString(1).toUpperCase(Locale.ROOT));
                if (m.matches()) {
                    highest = Math.max(highest, Integer.parseInt(m.group(1)));
                }
            }
        }
        return String.format(Locale.ROOT, "T%02d", highest + 1);
    }

    /* ------------------------------- helpers ------------------------------- */

    /** Adds the terminal, or updates its address (and computer name, when given). */
    private static void upsertTerminal(Connection c, String terminalId, String ipAddress, String computerName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO PosTerminals (TerminalID, IPAddress, ComputerName) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE IPAddress = VALUES(IPAddress), "
                        + "ComputerName = COALESCE(VALUES(ComputerName), ComputerName)")) {
            ps.setString(1, terminalId);
            ps.setString(2, ipAddress);
            ps.setString(3, computerName);
            ps.executeUpdate();
        }
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Runs {@code work} as one transaction on nickelsales_appconfig: all of it is saved, or none of it. */
    private static <T> T inTransaction(Work<T> work) {
        try (Connection c = AppConfigDatabase.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not save the POS terminal settings", e);
        }
    }
}
