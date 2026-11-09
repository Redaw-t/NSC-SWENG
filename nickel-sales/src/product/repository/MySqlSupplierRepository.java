package product.repository;

import product.model.Supplier;
import shared.Database;
import shared.MasterIds;
import shared.SyncOutbox;
import shared.SyncTables;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SupplierRepository backed by the Supplier table. All SQL for suppliers lives here. */
public class MySqlSupplierRepository implements SupplierRepository {

    private static final String SELECT =
            "SELECT SupplierID, SupplierName, ContactPerson, Phone, Email, Address, IsActive FROM Supplier";

    @Override
    public List<Supplier> findAll(boolean activeOnly) {
        String sql = SELECT + (activeOnly ? " WHERE IsActive = TRUE" : "") + " ORDER BY SupplierName";
        List<Supplier> found = new ArrayList<>();
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                found.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not load suppliers", e);
        }
        return found;
    }

    @Override
    public Optional<Supplier> findById(int supplierId) {
        return queryOne(SELECT + " WHERE SupplierID = ?", ps -> ps.setInt(1, supplierId));
    }

    @Override
    public Optional<Supplier> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return queryOne(SELECT + " WHERE SupplierName = ?", ps -> ps.setString(1, name.trim()));
    }

    /**
     * Saves a new supplier. Its ID comes from the Main POS when the terminals sync ({@link MasterIds}),
     * so two terminals never make the same one; otherwise MySQL numbers it.
     */
    @Override
    public Supplier insert(Supplier s) {
        Integer givenId = MasterIds.nextNumber(MasterIds.SUPPLIER);
        String sql = givenId == null
                ? "INSERT INTO Supplier (SupplierName, ContactPerson, Phone, Email, Address, IsActive) "
                        + "VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT INTO Supplier (SupplierName, ContactPerson, Phone, Email, Address, IsActive, SupplierID) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try {
            return Database.inTransaction(c -> {
                int id;
                try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, s.name());
                    ps.setString(2, s.contactPerson());
                    ps.setString(3, s.phone());
                    ps.setString(4, s.email());
                    ps.setString(5, s.address());
                    ps.setBoolean(6, s.active());
                    if (givenId != null) {
                        ps.setInt(7, givenId);
                    }
                    ps.executeUpdate();
                    if (givenId != null) {
                        id = givenId;
                    } else {
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next();
                            id = keys.getInt(1);
                        }
                    }
                }
                SyncOutbox.recordRows(c, SyncTables.SUPPLIER, String.valueOf(id), "Supplier", "SupplierID = ?", id);
                return new Supplier(id, s.name(), s.contactPerson(), s.phone(), s.email(), s.address(), s.active());
            });
        } catch (SQLException e) {
            throw MySqlErrors.translate(e, "Could not save supplier " + s.name());
        }
    }

    @Override
    public void update(Supplier s) {
        MasterIds.requireMain();   // shared by every terminal: changed only while the Main POS is reachable
        String sql = "UPDATE Supplier SET SupplierName = ?, ContactPerson = ?, Phone = ?, Email = ?, "
                + "Address = ?, IsActive = ? WHERE SupplierID = ?";
        try {
            Database.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, s.name());
                    ps.setString(2, s.contactPerson());
                    ps.setString(3, s.phone());
                    ps.setString(4, s.email());
                    ps.setString(5, s.address());
                    ps.setBoolean(6, s.active());
                    ps.setInt(7, s.supplierId());
                    if (ps.executeUpdate() == 0) {
                        throw new RuntimeException("No such supplier: " + s.supplierId());
                    }
                }
                SyncOutbox.recordRows(c, SyncTables.SUPPLIER, String.valueOf(s.supplierId()), "Supplier",
                        "SupplierID = ?", s.supplierId());
                return null;
            });
        } catch (SQLException e) {
            throw MySqlErrors.translate(e, "Could not update supplier " + s.supplierId());
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Supplier> queryOne(String sql, Binder binder) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error while reading Supplier", e);
        }
    }

    private static Supplier map(ResultSet rs) throws SQLException {
        return new Supplier(rs.getInt("SupplierID"), rs.getString("SupplierName"),
                rs.getString("ContactPerson"), rs.getString("Phone"), rs.getString("Email"),
                rs.getString("Address"), rs.getBoolean("IsActive"));
    }
}
