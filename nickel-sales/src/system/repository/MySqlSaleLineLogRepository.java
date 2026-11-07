package system.repository;

import pos.contract.SaleLineRecord;
import shared.Database;
import system.model.SaleLineLogEntry;
import system.model.SaleLineLogFilter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * POS's SaleLine table joined to Sale; SELECT only. An open item is a line whose ProductID starts
 * with OPEN-; an edited price is a line with SaleLine.OriginalPrice set.
 */
public class MySqlSaleLineLogRepository implements SaleLineLogRepository {

    private static final String OPEN = "l.ProductID LIKE '" + SaleLineRecord.OPEN_ITEM_PREFIX + "%'";
    private static final String EDITED = "l.OriginalPrice IS NOT NULL";

    @Override
    public List<SaleLineLogEntry> find(SaleLineLogFilter filter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT s.SaleID, s.InvoiceNo, s.CompletedAt, s.CashierUserID, "
                + "l.LineNo, l.ProductID, l.ProductName, l.Unit, l.Quantity, l.UnitPrice, l.OriginalPrice, "
                + "l.LineTotal, l.Vatable FROM SaleLine l JOIN Sale s ON s.SaleID = l.SaleID WHERE ");
        if (SaleLineLogEntry.OPEN_ITEM.equals(filter.type())) {
            sql.append(OPEN);
        } else if (SaleLineLogEntry.PRICE_EDIT.equals(filter.type())) {
            sql.append(EDITED);
        } else {
            sql.append("(").append(OPEN).append(" OR ").append(EDITED).append(")");
        }
        List<Object> args = new ArrayList<>();
        Sql.dateRange(sql, args, "s.CompletedAt", filter.from(), filter.to());
        if (filter.keyword() != null) {
            sql.append(" AND (s.InvoiceNo LIKE ? OR l.ProductName LIKE ? OR l.ProductID LIKE ?)");
            String like = Sql.contains(filter.keyword());
            for (int i = 0; i < 3; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY s.CompletedAt DESC, s.SaleID, l.LineNo LIMIT ?");
        args.add(limit);

        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            Sql.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<SaleLineLogEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SaleLineLogEntry(rs.getString("SaleID"), rs.getString("InvoiceNo"),
                            Sql.instant(rs, "CompletedAt"), rs.getString("CashierUserID"), rs.getInt("LineNo"),
                            rs.getString("ProductID"), rs.getString("ProductName"), rs.getString("Unit"),
                            rs.getBigDecimal("Quantity"), rs.getBigDecimal("UnitPrice"),
                            rs.getBigDecimal("OriginalPrice"), rs.getBigDecimal("LineTotal"),
                            rs.getBoolean("Vatable")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }
}
