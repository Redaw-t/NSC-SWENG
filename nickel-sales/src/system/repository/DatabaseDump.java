package system.repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the whole database as SQL text and reads it back, through the app's own JDBC connection,
 * so a terminal needs nothing besides the app (no mysqldump on the PATH).
 *
 * File format: one statement per line, comments start with "--". The first line says it is a
 * backup, and the last line gives the statement count, so a cut-off or edited file is refused
 * before anything is touched:
 *
 *   -- NICKEL SALES CENTER BACKUP v1 created=2026-11-04T04:36:00Z terminal=T01
 *   SET time_zone='+00:00';
 *   SET FOREIGN_KEY_CHECKS=0;
 *   DROP TABLE IF EXISTS `Users`;
 *   CREATE TABLE `Users` (...);
 *   INSERT INTO `Users` (...) VALUES (...),(...);
 *   SET FOREIGN_KEY_CHECKS=1;
 *   -- END OF BACKUP tables=14 statements=52
 */
public final class DatabaseDump {

    /** What the first and last lines of a good backup say. */
    public record Summary(String created, String terminal, int tables, int statements) { }

    private static final String HEADER = "-- NICKEL SALES CENTER BACKUP v1";
    private static final Pattern HEADER_LINE = Pattern.compile(
            "^-- NICKEL SALES CENTER BACKUP v1 created=(\\S+) terminal=(\\S+)$");
    private static final Pattern END_LINE = Pattern.compile("^-- END OF BACKUP tables=(\\d+) statements=(\\d+)$");
    private static final int ROWS_PER_INSERT = 200;

    private static final Set<String> NUMBER_TYPES = Set.of("tinyint", "smallint", "mediumint", "int", "integer",
            "bigint", "decimal", "numeric", "float", "double", "year");
    private static final Set<String> BINARY_TYPES = Set.of("binary", "varbinary", "tinyblob", "blob", "mediumblob",
            "longblob", "bit");

    private record Column(String name, String type) { }

    private DatabaseDump() { }

    /**
     * Writes every table of {@code c}'s database to {@code out}, as one consistent snapshot: a sale
     * made on another terminal meanwhile is either wholly in the backup or wholly not.
     *
     * @return the summary written on the last line
     */
    public static Summary write(Connection c, Writer out, Instant createdAt, String terminalId)
            throws SQLException, IOException {
        boolean autoCommit = c.getAutoCommit();
        int isolation = c.getTransactionIsolation();
        try (Statement setup = c.createStatement()) {
            setup.execute("SET time_zone='+00:00'");   // TIMESTAMP columns are written and read back unchanged
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            setup.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");

            out.write(HEADER + " created=" + createdAt + " terminal=" + terminalId + "\n");
            int statements = 0;
            statements += line(out, "SET time_zone='+00:00';");
            statements += line(out, "SET FOREIGN_KEY_CHECKS=0;");
            List<String> tables = tables(c);
            for (String table : tables) {
                statements += line(out, "DROP TABLE IF EXISTS " + quoted(table) + ";");
                statements += line(out, createStatement(c, table) + ";");
                statements += writeRows(c, out, table);
            }
            statements += line(out, "SET FOREIGN_KEY_CHECKS=1;");
            out.write("-- END OF BACKUP tables=" + tables.size() + " statements=" + statements + "\n");
            out.flush();
            return new Summary(createdAt.toString(), terminalId, tables.size(), statements);
        } finally {
            if (!c.getAutoCommit()) {
                c.rollback();   // only ever read; ends the snapshot
            }
            c.setAutoCommit(autoCommit);
            c.setTransactionIsolation(isolation);
        }
    }

    /**
     * Reads a backup to the end and checks it is whole. Changes nothing.
     *
     * @throws IllegalArgumentException it is not a complete backup; the message says why
     */
    public static Summary validate(BufferedReader in) throws IOException {
        String first = in.readLine();
        Matcher header = first == null ? null : HEADER_LINE.matcher(first);
        if (header == null || !header.matches()) {
            throw new IllegalArgumentException("This file is not a Nickel Sales Center backup.");
        }
        int statements = 0;
        Matcher end = null;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("--")) {
                Matcher m = END_LINE.matcher(line);
                if (m.matches()) {
                    end = m;
                }
            } else if (!line.isBlank()) {
                if (end != null) {
                    throw new IllegalArgumentException("This backup file has been changed and cannot be used.");
                }
                statements++;
            }
        }
        if (end == null) {
            throw new IllegalArgumentException("This backup file is incomplete: it may have been cut off while saving.");
        }
        try {
            Instant.parse(header.group(1));   // the restore screen shows this date
            if (Integer.parseInt(end.group(2)) != statements) {
                throw new IllegalArgumentException("This backup file has been changed and cannot be used.");
            }
            return new Summary(header.group(1), header.group(2), Integer.parseInt(end.group(1)), statements);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("This backup file has been changed and cannot be used.", e);
        }
    }

    /**
     * Replaces the tables in {@code c}'s database with the ones in the backup. Call
     * {@link #validate} first: MySQL cannot roll back DROP TABLE, so a failure part-way leaves a
     * mix of old and new tables.
     *
     * @return how many statements were run
     */
    public static int restore(Connection c, BufferedReader in) throws SQLException, IOException {
        int count = 0;
        try (Statement s = c.createStatement()) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.startsWith("--")) {
                    continue;
                }
                s.execute(line.endsWith(";") ? line.substring(0, line.length() - 1) : line);
                count++;
            }
            s.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        return count;
    }

    /* ------------------------------ writing ------------------------------ */

    private static int line(Writer out, String statement) throws IOException {
        out.write(statement);
        out.write('\n');
        return 1;
    }

    private static List<String> tables(Connection c) throws SQLException {
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    /** SHOW CREATE TABLE on one line, because the file holds one statement per line. */
    private static String createStatement(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW CREATE TABLE " + quoted(table))) {
            if (!rs.next()) {
                throw new SQLException("No definition for table " + table);
            }
            return rs.getString(2).replace("\r", " ").replace("\n", " ");
        }
    }

    private static List<Column> columns(Connection c, String table) throws SQLException {
        // Computed columns cannot be inserted. Not plain '%GENERATED%': MySQL also marks a column with
        // DEFAULT CURRENT_TIMESTAMP as DEFAULT_GENERATED, and those values must be kept.
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ? AND EXTRA NOT LIKE '%VIRTUAL GENERATED%' "
                + "AND EXTRA NOT LIKE '%STORED GENERATED%' ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<Column> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Column(rs.getString(1), rs.getString(2).toLowerCase(java.util.Locale.ROOT)));
                }
                return out;
            }
        }
    }

    /** Writes the table's rows as INSERTs of up to {@link #ROWS_PER_INSERT} rows; returns how many statements. */
    private static int writeRows(Connection c, Writer out, String table) throws SQLException, IOException {
        List<Column> columns = columns(c, table);
        StringBuilder select = new StringBuilder("SELECT ");
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            if (i > 0) {
                select.append(", ");
                names.append(",");
            }
            // As text, so a number, date or flag is written exactly as MySQL holds it.
            select.append(BINARY_TYPES.contains(col.type()) ? "HEX(" + quoted(col.name()) + ")"
                    : "CAST(" + quoted(col.name()) + " AS CHAR)");
            names.append(quoted(col.name()));
        }
        select.append(" FROM ").append(quoted(table));
        String insert = "INSERT INTO " + quoted(table) + " (" + names + ") VALUES ";

        int statements = 0;
        try (Statement s = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            s.setFetchSize(Integer.MIN_VALUE);   // row by row, so a big table is not held in memory
            try (ResultSet rs = s.executeQuery(select.toString())) {
                int inBatch = 0;
                while (rs.next()) {
                    out.write(inBatch == 0 ? insert : ",");
                    out.write(row(rs, columns));
                    if (++inBatch == ROWS_PER_INSERT) {
                        out.write(";\n");
                        statements++;
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    out.write(";\n");
                    statements++;
                }
            }
        }
        return statements;
    }

    private static String row(ResultSet rs, List<Column> columns) throws SQLException {
        StringBuilder row = new StringBuilder("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            String value = rs.getString(i + 1);
            String type = columns.get(i).type();
            if (value == null) {
                row.append("NULL");
            } else if (NUMBER_TYPES.contains(type)) {
                row.append(value);
            } else if (BINARY_TYPES.contains(type)) {
                row.append("X'").append(value).append('\'');
            } else {
                row.append(text(value));
            }
        }
        return row.append(')').toString();
    }

    /** A quoted SQL string on one line. */
    static String text(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\0' -> out.append("\\0");
                case '\u001A' -> out.append("\\Z");
                default -> out.append(ch);
            }
        }
        return out.append('\'').toString();
    }

    private static String quoted(String name) {
        return "`" + name.replace("`", "``") + "`";
    }
}
