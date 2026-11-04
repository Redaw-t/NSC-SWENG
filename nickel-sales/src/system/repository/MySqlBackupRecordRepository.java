package system.repository;

import shared.Database;
import system.model.BackupRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The BackupRecord table (see database/NickelSales-Database-Config.sql). */
public class MySqlBackupRecordRepository implements BackupRecordRepository {

    private static final String SELECT = "SELECT BackupID, CreatedAt, TerminalID, Kind, FileName, FolderPath, "
            + "SizeBytes, Result, Message, UserID FROM BackupRecord";

    @Override
    public void add(BackupRecord r) {
        String sql = "INSERT INTO BackupRecord (CreatedAt, TerminalID, Kind, FileName, FolderPath, SizeBytes, Result, "
                + "Message, UserID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, Sql.utc(r.createdAt()));
            ps.setString(2, Sql.fit(r.terminalId(), 10));
            ps.setString(3, r.kind());
            ps.setString(4, Sql.fit(r.fileName(), 255));
            ps.setString(5, Sql.fit(r.folderPath(), 500));
            if (r.sizeBytes() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, r.sizeBytes());
            }
            ps.setString(7, r.result());
            Sql.setNullable(ps, 8, Sql.fit(r.message(), 255));
            Sql.setNullable(ps, 9, Sql.fit(r.userId(), 10));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }

    @Override
    public List<BackupRecord> findRecent(int limit) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT + " ORDER BY BackupID DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<BackupRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }

    @Override
    public Optional<BackupRecord> latestBackup() {
        String sql = SELECT + " WHERE Kind = ? AND Result = ? ORDER BY BackupID DESC LIMIT 1";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, BackupRecord.BACKUP);
            ps.setString(2, BackupRecord.SUCCESS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }

    private static BackupRecord read(ResultSet rs) throws SQLException {
        long size = rs.getLong("SizeBytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        return new BackupRecord(rs.getLong("BackupID"), Sql.instant(rs, "CreatedAt"), rs.getString("TerminalID"),
                rs.getString("Kind"), rs.getString("FileName"), rs.getString("FolderPath"), sizeBytes,
                rs.getString("Result"), rs.getString("Message"), rs.getString("UserID"));
    }
}
