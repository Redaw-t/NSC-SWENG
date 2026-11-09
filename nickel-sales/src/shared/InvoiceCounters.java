package shared;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where each register's invoice numbers have got to (table InvoiceSequence: one row per terminal per
 * year, NextNumber = the next number it will use), kept on EVERY PC, not only on the register itself:
 *
 * - every PC raises a register's row whenever it gets one of that register's sales from sync
 *   ({@link #atLeast}, called by sync.ChangeApplier), so the rows travel with the sales;
 * - a register taking its next number also looks at the highest of its numbers already in the database
 *   ({@link #highestUsed}), so a counter that went back (the PC set up again, a backup restored) never
 *   repeats a number that exists.
 *
 * So if a register's database is lost, the copy it gets from the Main POS when it is set up again
 * already knows where it left off. The counter rows are never sent as such: a sale is, and only ever
 * raises a counter, never lowers it.
 */
public final class InvoiceCounters {

    /** T02-2026-000123: terminal, year, number. */
    private static final Pattern INVOICE = Pattern.compile("^([A-Za-z0-9-]{1,8})-(\\d{4})-(\\d+)$");

    /** An invoice number taken apart. */
    public record Parts(String terminalId, int year, long number) { }

    private InvoiceCounters() { }

    /** The parts of {@code invoiceNo}; empty if it isn't in the T02-2026-000123 form. */
    public static Optional<Parts> parse(String invoiceNo) {
        if (invoiceNo == null) {
            return Optional.empty();
        }
        Matcher m = INVOICE.matcher(invoiceNo.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parts(m.group(1), Integer.parseInt(m.group(2)), Long.parseLong(m.group(3))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Makes sure the register's counter for that year is past {@code invoiceNo} (raised if lower, never
     * lowered), in the caller's transaction. Does nothing for a number not in the usual form.
     */
    public static void atLeastPast(Connection tx, String invoiceNo) throws SQLException {
        Optional<Parts> p = parse(invoiceNo);
        if (p.isPresent()) {
            atLeast(tx, p.get().terminalId(), p.get().year(), p.get().number() + 1);
        }
    }

    /** Raises the counter of {@code terminalId} in {@code year} to at least {@code nextNumber} (never lowers it). */
    public static void atLeast(Connection tx, String terminalId, int year, long nextNumber) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO InvoiceSequence (TerminalID, InvoiceYear, "
                + "NextNumber) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE NextNumber = GREATEST(NextNumber, ?)")) {
            ps.setString(1, terminalId);
            ps.setInt(2, year);
            ps.setLong(3, nextNumber);
            ps.setLong(4, nextNumber);
            ps.executeUpdate();
        }
    }

    /** The highest invoice number of {@code terminalId} in {@code year} already saved here (0 if none). */
    public static long highestUsed(Connection tx, String terminalId, int year) throws SQLException {
        String prefix = terminalId + "-" + String.format("%04d", year) + "-";
        try (PreparedStatement ps = tx.prepareStatement("SELECT COALESCE(MAX(CAST(SUBSTRING(InvoiceNo, ?) AS UNSIGNED)), 0) "
                + "FROM Sale WHERE InvoiceNo LIKE ?")) {
            ps.setInt(1, prefix.length() + 1);
            ps.setString(2, prefix.replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
