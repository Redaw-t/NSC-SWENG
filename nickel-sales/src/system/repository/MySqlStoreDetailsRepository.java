package system.repository;

import pos.receipt.StoreInfo;
import shared.Database;
import system.model.StoreDetailsRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The StoreDetails table (see database/NickelSales-Database-Config.sql). */
public class MySqlStoreDetailsRepository implements StoreDetailsRepository {

    private static final String SELECT = "SELECT StoreDetailsID, StoreName, Address, Proprietor, TIN, Phone, "
            + "PerformedBy, PerformedAt, TerminalID FROM StoreDetails";

    @Override
    public StoreDetailsRecord add(StoreDetailsRecord r) {
        String sql = "INSERT INTO StoreDetails (StoreName, Address, Proprietor, TIN, Phone, PerformedBy, PerformedAt, "
                + "TerminalID) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            StoreInfo s = r.store();
            ps.setString(1, s.name());
            ps.setString(2, s.address());
            ps.setString(3, s.proprietor());
            ps.setString(4, s.tin());
            ps.setString(5, s.phone());
            ps.setString(6, r.performedBy());
            ps.setObject(7, Sql.utc(r.performedAt()));
            ps.setString(8, Sql.fit(r.terminalId(), 10));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new StoreDetailsRecord(id, s, r.performedBy(), r.performedAt(), r.terminalId());
            }
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }

    @Override
    public Optional<StoreDetailsRecord> latest() {
        List<StoreDetailsRecord> rows = history(1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<StoreDetailsRecord> history(int limit) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT + " ORDER BY StoreDetailsID DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<StoreDetailsRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new StoreDetailsRecord(rs.getLong("StoreDetailsID"),
                            new StoreInfo(rs.getString("StoreName"), rs.getString("Address"),
                                    rs.getString("Proprietor"), rs.getString("TIN"), rs.getString("Phone")),
                            rs.getString("PerformedBy"), Sql.instant(rs, "PerformedAt"), rs.getString("TerminalID")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }
}
