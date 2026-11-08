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
 * POS's SaleLine table joined to Sale, plus the voids of those lines (ReturnLine joined to `Return`
 * and back to its SaleLine); SELECT only. An open item is a line whose ProductID starts with OPEN-;
 * an edited price is a line with SaleLine.OriginalPrice set. A void is listed with minus quantity and
 * amount, its return number, when it was voided and who voided it.
 */
public class MySqlSaleLineLogRepository implements SaleLineLogRepository {

    private static final String OPEN = "l.ProductID LIKE '" + SaleLineRecord.OPEN_ITEM_PREFIX + "%'";
    private static final String EDITED = "l.OriginalPrice IS NOT NULL";

    @Override
    public List<SaleLineLogEntry> find(SaleLineLogFilter filter, int limit) {
        String which;
        if (SaleLineLogEntry.OPEN_ITEM.equals(filter.type())) {
            which = OPEN;
        } else if (SaleLineLogEntry.PRICE_EDIT.equals(filter.type())) {
            which = EDITED;
        } else {
            which = "(" + OPEN + " OR " + EDITED + ")";
        }
        boolean onlyVoids = SaleLineLogEntry.VOIDED.equals(filter.type());

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM (");
        if (!onlyVoids) {
            sql.append("SELECT s.SaleID, s.InvoiceNo, s.CompletedAt AS LoggedAt, s.CashierUserID AS Cashier, "
                    + "l.LineNo, l.ProductID, l.ProductName, l.Unit, l.Quantity, l.UnitPrice, l.OriginalPrice, "
                    + "l.LineTotal, l.Vatable, NULL AS ReturnNo "
                    + "FROM SaleLine l JOIN Sale s ON s.SaleID = l.SaleID WHERE ").append(which);
            Sql.dateRange(sql, args, "s.CompletedAt", filter.from(), filter.to());
            keyword(sql, args, filter.keyword(), "s.InvoiceNo");
            sql.append(" UNION ALL ");
        }
        // voids of those lines: the original SaleLine decides whether it was an open item / price edit
        sql.append("SELECT r.SaleID, r.InvoiceNo, r.ReturnedAt AS LoggedAt, r.CashierUserID AS Cashier, "
                + "rl.SaleLineNo AS LineNo, l.ProductID, l.ProductName, l.Unit, -rl.Quantity AS Quantity, "
                + "rl.UnitPrice, l.OriginalPrice, -rl.LineTotal AS LineTotal, rl.Vatable, r.ReturnNo "
                + "FROM ReturnLine rl JOIN `Return` r ON r.ReturnID = rl.ReturnID "
                + "JOIN SaleLine l ON l.SaleID = rl.SaleID AND l.LineNo = rl.SaleLineNo WHERE ").append(which);
        Sql.dateRange(sql, args, "r.ReturnedAt", filter.from(), filter.to());
        keyword(sql, args, filter.keyword(), "r.ReturnNo");
        sql.append(") x ORDER BY LoggedAt DESC, SaleID, LineNo LIMIT ?");
        args.add(limit);

        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            Sql.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<SaleLineLogEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SaleLineLogEntry(rs.getString("SaleID"), rs.getString("InvoiceNo"),
                            Sql.instant(rs, "LoggedAt"), rs.getString("Cashier"), rs.getInt("LineNo"),
                            rs.getString("ProductID"), rs.getString("ProductName"), rs.getString("Unit"),
                            rs.getBigDecimal("Quantity"), rs.getBigDecimal("UnitPrice"),
                            rs.getBigDecimal("OriginalPrice"), rs.getBigDecimal("LineTotal"),
                            rs.getBoolean("Vatable"), rs.getString("ReturnNo")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw Sql.failure(e);
        }
    }

    /** The keyword is looked for in the number ({@code numberColumn}), the item's name and its product ID. */
    private static void keyword(StringBuilder sql, List<Object> args, String keyword, String numberColumn) {
        if (keyword == null) {
            return;
        }
        sql.append(" AND (").append(numberColumn).append(" LIKE ? OR l.ProductName LIKE ? OR l.ProductID LIKE ?)");
        String like = Sql.contains(keyword);
        for (int i = 0; i < 3; i++) {
            args.add(like);
        }
    }
}
