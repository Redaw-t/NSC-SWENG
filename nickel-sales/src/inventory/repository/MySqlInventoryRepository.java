package inventory.repository;

import inventory.model.MovementType;
import inventory.model.StockLevel;
import inventory.model.StockMovement;
import inventory.model.StockReason;
import shared.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL storage for stock levels (table Inventory) and the movement history
 * (table StockMovement). Times are stored as UTC DATETIME(3); they are
 * converted by hand so the JVM's time zone can never shift them.
 */
public class MySqlInventoryRepository implements InventoryRepository {

    private static final String SELECT_LEVEL =
            "SELECT ProductID, QuantityOnHand, ReorderLevel, UpdatedAt FROM Inventory";

    @Override
    public Optional<StockLevel> find(String productId) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_LEVEL + " WHERE ProductID = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readLevel(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public List<StockLevel> findAll() {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_LEVEL + " ORDER BY ProductID");
             ResultSet rs = ps.executeQuery()) {
            List<StockLevel> out = new ArrayList<>();
            while (rs.next()) {
                out.add(readLevel(rs));
            }
            return out;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public List<StockMovement> findMovements(String productId, int limit) {
        String sql = "SELECT MovementID, ProductID, MovementType, Quantity, QuantityBefore, QuantityAfter, "
                + "Reason, ReferenceNo, Note, PerformedBy, CreatedAt FROM StockMovement"
                + (productId == null ? "" : " WHERE ProductID = ?") + " ORDER BY MovementID DESC LIMIT ?";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (productId != null) {
                ps.setString(i++, productId);
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<StockMovement> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new StockMovement(rs.getLong(1), rs.getString(2),
                            MovementType.valueOf(rs.getString(3)),
                            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                            StockReason.fromDb(rs.getString(7)),
                            rs.getString(8), rs.getString(9), rs.getString(10),
                            rs.getObject(11, LocalDateTime.class).toInstant(ZoneOffset.UTC)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public <T, E extends Exception> T inTransaction(Database.TransactionWork<T, E> work) throws E {
        try {
            return Database.inTransaction(work);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    @Override
    public Optional<StockLevel> lock(Connection tx, String productId) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(SELECT_LEVEL + " WHERE ProductID = ? FOR UPDATE")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readLevel(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public StockLevel lockOrCreate(Connection tx, String productId, Instant now) throws SQLException {
        try (PreparedStatement ensure = tx.prepareStatement(
                "INSERT INTO Inventory (ProductID, QuantityOnHand, ReorderLevel, UpdatedAt) "
                        + "VALUES (?, 0, 0, ?) ON DUPLICATE KEY UPDATE ProductID = ProductID")) {
            ensure.setString(1, productId);
            ensure.setObject(2, utc(now));
            ensure.executeUpdate();
        }
        return lock(tx, productId)
                .orElseThrow(() -> new SQLException("Inventory row missing for " + productId));
    }

    @Override
    public void update(Connection tx, StockLevel level) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "UPDATE Inventory SET QuantityOnHand = ?, ReorderLevel = ?, UpdatedAt = ? WHERE ProductID = ?")) {
            ps.setBigDecimal(1, level.quantityOnHand());
            ps.setBigDecimal(2, level.reorderLevel());
            ps.setObject(3, utc(level.updatedAt()));
            ps.setString(4, level.productId());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Inventory row missing for " + level.productId());
            }
        }
    }

    @Override
    public void addMovement(Connection tx, StockMovement m) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO StockMovement (ProductID, MovementType, Quantity, QuantityBefore, QuantityAfter, "
                        + "Reason, ReferenceNo, Note, PerformedBy, CreatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, m.productId());
            ps.setString(2, m.type().name());
            ps.setBigDecimal(3, m.quantity());
            ps.setBigDecimal(4, m.quantityBefore());
            ps.setBigDecimal(5, m.quantityAfter());
            setNullable(ps, 6, m.reason() == null ? null : m.reason().name());
            setNullable(ps, 7, m.referenceNo());
            setNullable(ps, 8, m.note());
            setNullable(ps, 9, m.performedBy());
            ps.setObject(10, utc(m.createdAt()));
            ps.executeUpdate();
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static StockLevel readLevel(ResultSet rs) throws SQLException {
        LocalDateTime updated = rs.getObject(4, LocalDateTime.class);
        return new StockLevel(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                updated == null ? null : updated.toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static RuntimeException failure(SQLException e) {
        return new RuntimeException(e.getMessage(), e);
    }
}
