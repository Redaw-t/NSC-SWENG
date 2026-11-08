package pos.repository;

import pos.contract.PaymentMethod;
import pos.contract.PriceType;
import pos.contract.ReturnHistory;
import pos.contract.ReturnLineRecord;
import pos.contract.ReturnRecord;
import shared.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Voids / returns in MySQL: tables `Return` and ReturnLine (RETURN is a reserved word, so the
 * table name is always written in backticks). Times are UTC in DATETIME(3), like Sale.
 *
 * The save and the in-transaction reads take the void's transaction connection and never commit,
 * roll back or close it (see pos.returns.ReturnService).
 */
public class MySqlReturnRepository implements ReturnHistory {

    private static final String COLUMNS = "ReturnID, ReturnNo, SaleID, InvoiceNo, TerminalID, CashierUserID, "
            + "ReturnedAt, FullVoid, RefundMethod, RefundReference, VatableSales, VatExemptSales, VatAmount, Total";

    /* ------------------------------ in the void's transaction ------------------------------ */

    /**
     * Locks the sale's row until the transaction ends, so two voids of the same sale (on this or the
     * other terminal) run one after the other and can never void the same item twice.
     *
     * @return false if there is no such sale
     */
    public boolean lockSale(Connection tx, String saleId) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT SaleID FROM Sale WHERE SaleID = ? FOR UPDATE")) {
            ps.setString(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The sale's returns, read with the void's transaction (after {@link #lockSale}). */
    public List<ReturnRecord> findBySaleId(Connection tx, String saleId) throws SQLException {
        return load(tx, "SaleID = ?", saleId);
    }

    public void save(ReturnRecord r, Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO `Return` (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, r.returnId());
            ps.setString(2, r.returnNo());
            ps.setString(3, r.saleId());
            ps.setString(4, r.invoiceNo());
            ps.setString(5, r.terminalId());
            ps.setString(6, r.cashierUserId());
            ps.setObject(7, MySqlSaleRepository.toDb(r.returnedAt()));
            ps.setBoolean(8, r.fullVoid());
            ps.setString(9, r.refundMethod().name());
            ps.setString(10, r.refundReference());
            ps.setBigDecimal(11, r.vatableSales());
            ps.setBigDecimal(12, r.vatExemptSales());
            ps.setBigDecimal(13, r.vatAmount());
            ps.setBigDecimal(14, r.total());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO ReturnLine (ReturnID, LineNo, SaleID, SaleLineNo, ReturnNo, ProductID, Barcode, "
                        + "ProductName, Unit, Quantity, UnitPrice, PriceType, Vatable, LineTotal) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (ReturnLineRecord line : r.lines()) {
                ps.setString(1, r.returnId());
                ps.setInt(2, line.lineNo());
                ps.setString(3, r.saleId());
                ps.setInt(4, line.saleLineNo());
                ps.setString(5, r.returnNo());
                ps.setString(6, line.productId());
                ps.setString(7, line.barcode());
                ps.setString(8, line.productName());
                ps.setString(9, line.unit());
                ps.setBigDecimal(10, line.quantity());
                ps.setBigDecimal(11, line.unitPrice());
                ps.setString(12, line.priceType().name());
                ps.setBoolean(13, line.vatable());
                ps.setBigDecimal(14, line.lineTotal());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /* ----------------------------------- ReturnHistory ----------------------------------- */

    @Override
    public List<ReturnRecord> findBySaleId(String saleId) {
        try (Connection c = Database.getConnection()) {
            return load(c, "SaleID = ?", saleId == null ? "" : saleId);
        } catch (SQLException e) {
            throw new RuntimeException("Could not read the voids of this sale", e);
        }
    }

    @Override
    public Optional<ReturnRecord> findByReturnNo(String returnNo) {
        try (Connection c = Database.getConnection()) {
            String no = returnNo == null ? "" : returnNo.trim();
            // as printed (T01-2026-000123-01) or as the slip's barcode holds it, without dashes (T01202600012301)
            return load(c, no.contains("-") ? "ReturnNo = ?" : "REPLACE(ReturnNo, '-', '') = ?", no)
                    .stream().findFirst();
        } catch (SQLException e) {
            throw new RuntimeException("Could not read the return " + returnNo, e);
        }
    }

    @Override
    public List<ReturnRecord> findReturned(java.time.Instant from, java.time.Instant to) {
        try (Connection c = Database.getConnection()) {
            return load(c, "ReturnedAt >= ? AND ReturnedAt < ?",
                    MySqlSaleRepository.toDb(from), MySqlSaleRepository.toDb(to));
        } catch (SQLException e) {
            throw new RuntimeException("Could not read the returns", e);
        }
    }

    @Override
    public List<ReturnRecord> findForSalesCompletedIn(java.time.Instant from, java.time.Instant to) {
        try (Connection c = Database.getConnection()) {
            return load(c, "SaleID IN (SELECT SaleID FROM Sale WHERE CompletedAt >= ? AND CompletedAt < ?)",
                    MySqlSaleRepository.toDb(from), MySqlSaleRepository.toDb(to));
        } catch (SQLException e) {
            throw new RuntimeException("Could not read the refunds of these sales", e);
        }
    }

    /* -------------------------------------- loading -------------------------------------- */

    private static List<ReturnRecord> load(Connection c, String where, Object... params) throws SQLException {
        Map<String, Object[]> heads = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM `Return` WHERE " + where + " ORDER BY ReturnedAt, ReturnNo")) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    heads.put(rs.getString("ReturnID"), new Object[] {
                            rs.getString("ReturnNo"), rs.getString("SaleID"), rs.getString("InvoiceNo"),
                            rs.getString("TerminalID"), rs.getString("CashierUserID"),
                            MySqlSaleRepository.fromDb(rs.getObject("ReturnedAt", LocalDateTime.class)),
                            rs.getBoolean("FullVoid"), PaymentMethod.valueOf(rs.getString("RefundMethod")),
                            rs.getString("RefundReference"),
                            rs.getBigDecimal("VatableSales"), rs.getBigDecimal("VatExemptSales"),
                            rs.getBigDecimal("VatAmount"), rs.getBigDecimal("Total")});
                }
            }
        }
        List<ReturnRecord> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : heads.entrySet()) {
            Object[] h = e.getValue();
            out.add(new ReturnRecord(e.getKey(), (String) h[0], (String) h[1], (String) h[2], (String) h[3],
                    (String) h[4], (java.time.Instant) h[5], (Boolean) h[6], (PaymentMethod) h[7], (String) h[8],
                    loadLines(c, e.getKey()), (java.math.BigDecimal) h[9], (java.math.BigDecimal) h[10],
                    (java.math.BigDecimal) h[11], (java.math.BigDecimal) h[12]));
        }
        return out;
    }

    private static List<ReturnLineRecord> loadLines(Connection c, String returnId) throws SQLException {
        List<ReturnLineRecord> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT LineNo, SaleLineNo, ProductID, Barcode, ProductName, Unit, Quantity, UnitPrice, PriceType, "
                        + "Vatable, LineTotal FROM ReturnLine WHERE ReturnID = ? ORDER BY LineNo")) {
            ps.setString(1, returnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new ReturnLineRecord(rs.getInt("LineNo"), rs.getInt("SaleLineNo"),
                            rs.getString("ProductID"), rs.getString("Barcode"), rs.getString("ProductName"),
                            rs.getString("Unit"), rs.getBigDecimal("Quantity"), rs.getBigDecimal("UnitPrice"),
                            PriceType.valueOf(rs.getString("PriceType")), rs.getBoolean("Vatable"),
                            rs.getBigDecimal("LineTotal")));
                }
            }
        }
        return lines;
    }
}
