package system.repository;

import shared.PhTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Small helpers the System module's MySQL repositories share. */
final class Sql {

    private Sql() { }

    /** An Instant as the UTC DATETIME(3) value the tables store. */
    static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    /** A UTC DATETIME(3) column read back as an Instant; null stays null. */
    static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** Adds "AND column >= start of from AND column < day after to" for a PH-time day range. */
    static void dateRange(StringBuilder sql, List<Object> args, String column, LocalDate from, LocalDate to) {
        if (from != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            args.add(utc(PhTime.startOf(from)));
        }
        if (to != null) {
            sql.append(" AND ").append(column).append(" < ?");
            args.add(utc(PhTime.startOf(to.plusDays(1))));
        }
    }

    /** "%text%" with a typed % or _ searched for as itself. */
    static String contains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** {@code value} cut to {@code max} characters (null stays null). */
    static String fit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    static RuntimeException failure(SQLException e) {
        return new RuntimeException(e.getMessage(), e);
    }
}
