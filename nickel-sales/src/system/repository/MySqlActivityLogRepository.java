package system.repository;

import shared.Database;
import shared.SyncOutbox;
import shared.SyncTables;
import system.model.ActivityDescription;
import system.model.ActivityEntry;
import system.model.ActivityFilter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The ActivityLog table (see database/NickelSales-Database-Config.sql). */
public class MySqlActivityLogRepository implements ActivityLogRepository {

    private static final String COLUMNS = "LogID, CreatedAt, UserID, TerminalID, Module, ActionType, Target, "
            + "Details, OldValue, NewValue";

    @Override
    public void append(ActivityEntry e) {
        String sql = "INSERT INTO ActivityLog (GlobalID, CreatedAt, UserID, TerminalID, Module, ActionType, Target, "
                + "Details, OldValue, NewValue) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String globalId = SyncOutbox.newId();   // the entry's ID on every terminal
        try {
            // One transaction: the entry and its sync queue row are saved together.
            Database.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, globalId);
                    ps.setObject(2, Sql.utc(e.createdAt()));
                    Sql.setNullable(ps, 3, Sql.fit(e.userId(), 30));
                    ps.setString(4, Sql.fit(e.terminalId(), 10));
                    ps.setString(5, Sql.fit(e.module(), 20));
                    ps.setString(6, Sql.fit(e.action(), 40));
                    Sql.setNullable(ps, 7, Sql.fit(e.target(), 50));
                    Sql.setNullable(ps, 8, Sql.fit(e.details(), 255));
                    Sql.setNullable(ps, 9, Sql.fit(e.oldValue(), 255));
                    Sql.setNullable(ps, 10, Sql.fit(e.newValue(), 255));
                    ps.executeUpdate();
                }
                SyncOutbox.recordRows(c, SyncTables.ACTIVITY, e.action(), "ActivityLog", "GlobalID = ?", globalId);
                return null;
            });
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public List<ActivityEntry> find(ActivityFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ActivityLog WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        Sql.dateRange(sql, args, "CreatedAt", filter.from(), filter.to());
        if (filter.userId() != null) {
            sql.append(" AND UserID = ?");
            args.add(filter.userId());
        }
        if (filter.module() != null) {
            sql.append(" AND Module = ?");
            args.add(filter.module());
        }
        if (filter.keyword() != null) {
            sql.append(" AND (ActionType LIKE ? OR UserID LIKE ? OR Target LIKE ? OR Details LIKE ?"
                    + " OR OldValue LIKE ? OR NewValue LIKE ?");
            String like = Sql.contains(filter.keyword());
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
            Set<String> worded = ActivityDescription.actionsMatching(filter.keyword());
            if (!worded.isEmpty()) {
                sql.append(" OR ActionType IN (").append("?,".repeat(worded.size() - 1)).append("?)");
                args.addAll(worded);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY LogID DESC LIMIT ?");
        args.add(limit);

        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            Sql.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<ActivityEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ActivityEntry(rs.getLong("LogID"), Sql.instant(rs, "CreatedAt"),
                            rs.getString("UserID"), rs.getString("TerminalID"), rs.getString("Module"),
                            rs.getString("ActionType"), rs.getString("Target"), rs.getString("Details"),
                            rs.getString("OldValue"), rs.getString("NewValue")));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public List<String> userIds() {
        String sql = "SELECT DISTINCT UserID FROM ActivityLog WHERE UserID IS NOT NULL ORDER BY UserID";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public Optional<Instant> oldest() {
        String sql = "SELECT MIN(CreatedAt) AS Oldest FROM ActivityLog";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(Sql.instant(rs, "Oldest")) : Optional.empty();
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public long countOlderThan(Instant time) {
        String sql = "SELECT COUNT(*) FROM ActivityLog WHERE CreatedAt < ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, Sql.utc(time));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }
}
