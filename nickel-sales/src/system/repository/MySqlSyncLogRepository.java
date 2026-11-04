package system.repository;

import shared.Database;
import system.model.SyncLogEntry;
import system.model.SyncLogFilter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The SyncLog table (see database/NickelSales-Database-Config.sql). */
public class MySqlSyncLogRepository implements SyncLogRepository {

    @Override
    public long add(SyncLogEntry e) {
        String sql = "INSERT INTO SyncLog (CreatedAt, UpdatedAt, SourceTerminal, TargetTerminal, DataType, RecordID, "
                + "Status, RetryCount, ErrorMessage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setObject(1, Sql.utc(e.createdAt()));
            ps.setObject(2, Sql.utc(e.updatedAt()));
            ps.setString(3, Sql.fit(e.sourceTerminal(), 10));
            ps.setString(4, Sql.fit(e.targetTerminal(), 10));
            ps.setString(5, Sql.fit(e.dataType(), 20));
            ps.setString(6, Sql.fit(e.recordId(), 50));
            ps.setString(7, e.status());
            ps.setInt(8, e.retryCount());
            Sql.setNullable(ps, 9, Sql.fit(e.errorMessage(), 255));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public void markSynced(long logId, Instant at) {
        String sql = "UPDATE SyncLog SET Status = ?, ErrorMessage = NULL, UpdatedAt = ? WHERE LogID = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, SyncLogEntry.SYNCED);
            ps.setObject(2, Sql.utc(at));
            ps.setLong(3, logId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public void markFailed(long logId, String errorMessage, Instant at) {
        String sql = "UPDATE SyncLog SET Status = ?, ErrorMessage = ?, RetryCount = RetryCount + 1, UpdatedAt = ? "
                + "WHERE LogID = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, SyncLogEntry.FAILED);
            Sql.setNullable(ps, 2, Sql.fit(errorMessage, 255));
            ps.setObject(3, Sql.utc(at));
            ps.setLong(4, logId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public List<SyncLogEntry> find(SyncLogFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT LogID, CreatedAt, UpdatedAt, SourceTerminal, TargetTerminal, "
                + "DataType, RecordID, Status, RetryCount, ErrorMessage FROM SyncLog WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        Sql.dateRange(sql, args, "CreatedAt", filter.from(), filter.to());
        if (filter.status() != null) {
            sql.append(" AND Status = ?");
            args.add(filter.status());
        }
        if (filter.keyword() != null) {
            sql.append(" AND (RecordID LIKE ? OR SourceTerminal LIKE ? OR TargetTerminal LIKE ? OR ErrorMessage LIKE ?)");
            String like = Sql.contains(filter.keyword());
            for (int i = 0; i < 4; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY LogID DESC LIMIT ?");
        args.add(limit);

        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            Sql.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<SyncLogEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SyncLogEntry(rs.getLong("LogID"), Sql.instant(rs, "CreatedAt"),
                            Sql.instant(rs, "UpdatedAt"), rs.getString("SourceTerminal"),
                            rs.getString("TargetTerminal"), rs.getString("DataType"), rs.getString("RecordID"),
                            rs.getString("Status"), rs.getInt("RetryCount"), rs.getString("ErrorMessage")));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }

    @Override
    public Optional<Instant> lastSuccess() {
        String sql = "SELECT MAX(UpdatedAt) AS Latest FROM SyncLog WHERE Status = ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, SyncLogEntry.SYNCED);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(Sql.instant(rs, "Latest")) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw Sql.failure(ex);
        }
    }
}
