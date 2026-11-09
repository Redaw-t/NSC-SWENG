package pos.repository;

import shared.PhTime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Gives out invoice numbers (InvoiceNumberGenerator in the class diagram):
 * {@code T01-2026-000123} = terminal, year in Philippine time, then a 6-digit
 * running number that starts again at 000001 each year (requirement FR-17).
 *
 * The next number is kept in the InvoiceSequence table, one row per terminal
 * per year. It is taken INSIDE the transaction that saves the sale, with the
 * row locked, so:
 * - if the sale fails and is rolled back, the number is not used up (no gaps);
 * - two sales on the same terminal can never get the same number.
 * It also never repeats a number already in the database, even if the counter went back
 * (see shared.InvoiceCounters: every PC also keeps every other register's counter, from the sales sync
 * brings it, so a PC set up again continues where it left off).
 */
public class InvoiceNumberGenerator {

    /** Terminal IDs are short codes such as "T01" (letters, digits, dashes; up to 8). */
    private static final Pattern TERMINAL_ID = Pattern.compile("[A-Za-z0-9-]{1,8}");

    /**
     * The next invoice number for {@code terminalId} in the PH year of
     * {@code completedAt}. Must be called with the sale's transaction
     * connection; the caller commits or rolls back.
     */
    public String nextInvoiceNo(Connection tx, String terminalId, Instant completedAt) throws SQLException {
        checkTerminalId(terminalId);
        int year = PhTime.year(completedAt);
        try (PreparedStatement ensure = tx.prepareStatement(
                "INSERT INTO InvoiceSequence (TerminalID, InvoiceYear, NextNumber) VALUES (?, ?, 1) "
                        + "ON DUPLICATE KEY UPDATE NextNumber = NextNumber")) {
            ensure.setString(1, terminalId);
            ensure.setInt(2, year);
            ensure.executeUpdate();
        }
        int number;
        try (PreparedStatement lock = tx.prepareStatement(
                "SELECT NextNumber FROM InvoiceSequence WHERE TerminalID = ? AND InvoiceYear = ? FOR UPDATE")) {
            lock.setString(1, terminalId);
            lock.setInt(2, year);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("InvoiceSequence row missing for " + terminalId + " " + year);
                }
                number = rs.getInt(1);
            }
        }
        // Never a number already used: if this counter went back (the PC was set up again from the Main
        // POS's copy, or a backup was restored), go past the highest of this register's invoices here.
        number = (int) Math.max(number, shared.InvoiceCounters.highestUsed(tx, terminalId, year) + 1);
        try (PreparedStatement next = tx.prepareStatement(
                "UPDATE InvoiceSequence SET NextNumber = ? WHERE TerminalID = ? AND InvoiceYear = ?")) {
            next.setInt(1, number + 1);
            next.setString(2, terminalId);
            next.setInt(3, year);
            next.executeUpdate();
        }
        return format(terminalId, year, number);
    }

    /** "T01-2026-000123". Numbers past 999999 just get longer. */
    public static String format(String terminalId, int year, int number) {
        return String.format("%s-%04d-%06d", terminalId, year, number);
    }

    /** Throws IllegalArgumentException for a terminal ID that can't go in an invoice number. */
    public static void checkTerminalId(String terminalId) {
        if (terminalId == null || !TERMINAL_ID.matcher(terminalId).matches()) {
            throw new IllegalArgumentException(
                    "Terminal ID must be 1-8 letters, digits or dashes (e.g. T01): " + terminalId);
        }
    }
}
