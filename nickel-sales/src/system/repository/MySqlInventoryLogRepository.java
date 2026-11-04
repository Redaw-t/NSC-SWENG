package system.repository;

import shared.Database;
import system.model.InventoryLogEntry;
import system.model.InventoryLogFilter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The StockMovement table (owned by Inventory), with the product's name; SELECT only. */
public class MySqlInventoryLogRepository implements InventoryLogRepository {

    @Override
    public List<InventoryLogEntry> find(InventoryLogFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT m.MovementID, m.CreatedAt, m.ProductID, p.ProductName, "
                + "m.MovementType, m.Quantity, m.QuantityBefore, m.QuantityAfter, m.Reason, m.ReferenceNo, m.Note, "
                + "m.PerformedBy FROM StockMovement m LEFT JOIN Product p ON p.ProductID = m.ProductID WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        Sql.dateRange(sql, args, "m.CreatedAt", filter.from(), filter.to());
        if (filter.type() != null) {
            sql.append(" AND m.MovementType = ?");
            args.add(filter.type());
        }
        if (filter.keyword() != null) {
            sql.append(" AND (m.ProductID LIKE ? OR p.ProductName LIKE ? OR p.Barcode LIKE ? OR m.ReferenceNo LIKE ?"
                    + " OR m.Note LIKE ?)");
            String like = Sql.contains(filter.keyword());
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY m.MovementID DESC LIMIT ?");
        args.add(limit);

        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            Sql.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<InventoryLogEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new InventoryLogEntry(rs.getLong("MovementID"), Sql.instant(rs, "CreatedAt"),
                            rs.getString("ProductID"), rs.getString("ProductName"), rs.getString("MovementType"),
                            rs.getBigDecimal("Quantity"), rs.getBigDecimal("QuantityBefore"),
                            rs.getBigDecimal("QuantityAfter"), rs.getString("Reason"), rs.getString("ReferenceNo"),
                            rs.getString("Note"), rs.getString("PerformedBy")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }
}
