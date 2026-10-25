package pos.repository;

import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleExport;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import shared.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Completed sales in MySQL: tables Sale, SaleLine and Payment.
 *
 * Implements:
 * - {@link SaleRepository}: save (POS, inside the checkout transaction);
 * - {@link pos.contract.SaleHistory}: read by ID, invoice number, date range
 *   (Reports, receipt reprint);
 * - {@link SaleExport}: unsynced sales and markSynced (Sync).
 *
 * Times are stored as UTC in DATETIME(3) (milliseconds). Database problems
 * in the read methods come out as RuntimeException (cause: the SQLException),
 * like the other repositories, so screens can use shared.UiErrors.
 */
public class MySqlSaleRepository implements SaleRepository, SaleExport {

    private static final String SALE_COLUMNS = "SaleID, InvoiceNo, TerminalID, CashierUserID, CompletedAt, "
            + "VatableSales, VatExemptSales, VatAmount, Total";

    /** IDs per IN (...) query when loading lines and payments. */
    private static final int CHUNK = 500;

    /* -------------------------------- save -------------------------------- */

    @Override
    public void save(SaleRecord sale, Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO Sale (" + SALE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, sale.saleId());
            ps.setString(2, sale.invoiceNo());
            ps.setString(3, sale.terminalId());
            ps.setString(4, sale.cashierUserId());
            ps.setObject(5, toDb(sale.completedAt()));
            ps.setBigDecimal(6, sale.vatableSales());
            ps.setBigDecimal(7, sale.vatExemptSales());
            ps.setBigDecimal(8, sale.vatAmount());
            ps.setBigDecimal(9, sale.total());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO SaleLine (SaleID, LineNo, ProductID, Barcode, ProductName, Unit, Quantity, "
                        + "UnitPrice, PriceType, Vatable, LineTotal) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (SaleLineRecord line : sale.lines()) {
                ps.setString(1, sale.saleId());
                ps.setInt(2, line.lineNo());
                ps.setString(3, line.productId());
                ps.setString(4, line.barcode());
                ps.setString(5, line.productName());
                ps.setString(6, line.unit());
                ps.setBigDecimal(7, line.quantity());
                ps.setBigDecimal(8, line.unitPrice());
                ps.setString(9, line.priceType().name());
                ps.setBoolean(10, line.vatable());
                ps.setBigDecimal(11, line.lineTotal());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = tx.prepareStatement(
                "INSERT INTO Payment (SaleID, PaymentNo, Method, ReferenceNo, AmountTendered, AmountApplied, "
                        + "ChangeGiven) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (PaymentRecord p : sale.payments()) {
                ps.setString(1, sale.saleId());
                ps.setInt(2, p.paymentNo());
                ps.setString(3, p.method().name());
                ps.setString(4, p.referenceNo());
                ps.setBigDecimal(5, p.amountTendered());
                ps.setBigDecimal(6, p.amountApplied());
                ps.setBigDecimal(7, p.change());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /* --------------------------- SaleHistory ------------------------------ */

    @Override
    public Optional<SaleRecord> findById(String saleId) {
        return load("SaleID = ?", List.of(Objects.requireNonNullElse(saleId, "")), 0).stream().findFirst();
    }

    @Override
    public Optional<SaleRecord> findByInvoiceNo(String invoiceNo) {
        String no = invoiceNo == null ? "" : invoiceNo.trim();
        return load("InvoiceNo = ?", List.of(no), 0).stream().findFirst();
    }

    @Override
    public List<SaleRecord> findCompleted(Instant from, Instant to) {
        return load("CompletedAt >= ? AND CompletedAt < ?", List.of(toDb(from), toDb(to)), 0);
    }

    /* ---------------------------- SaleExport ------------------------------ */

    @Override
    public List<SaleRecord> findUnsynced(int limit) {
        return limit <= 0 ? List.of() : load("SyncedAt IS NULL", List.of(), limit);
    }

    @Override
    public void markSynced(String saleId, Instant syncedAt) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE Sale SET SyncedAt = ? WHERE SaleID = ? AND SyncedAt IS NULL")) {
            ps.setObject(1, toDb(syncedAt));
            ps.setString(2, saleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not mark sale " + saleId + " as synced", e);
        }
    }

    /* ------------------------------ loading ------------------------------- */

    /** Sales matching {@code where}, oldest first, with their lines and payments. */
    private List<SaleRecord> load(String where, List<Object> params, int limit) {
        try (Connection c = Database.getConnection()) {
            Map<String, Object[]> heads = new LinkedHashMap<>();
            String sql = "SELECT " + SALE_COLUMNS + " FROM Sale WHERE " + where
                    + " ORDER BY CompletedAt, SaleID" + (limit > 0 ? " LIMIT ?" : "");
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                for (Object p : params) {
                    ps.setObject(i++, p);
                }
                if (limit > 0) {
                    ps.setInt(i, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        heads.put(rs.getString("SaleID"), new Object[] {
                                rs.getString("InvoiceNo"), rs.getString("TerminalID"),
                                rs.getString("CashierUserID"),
                                fromDb(rs.getObject("CompletedAt", LocalDateTime.class)),
                                rs.getBigDecimal("VatableSales"), rs.getBigDecimal("VatExemptSales"),
                                rs.getBigDecimal("VatAmount"), rs.getBigDecimal("Total")});
                    }
                }
            }
            if (heads.isEmpty()) {
                return List.of();
            }
            Map<String, List<SaleLineRecord>> lines = new LinkedHashMap<>();
            Map<String, List<PaymentRecord>> payments = new LinkedHashMap<>();
            List<String> ids = new ArrayList<>(heads.keySet());
            for (int from = 0; from < ids.size(); from += CHUNK) {
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
                loadLines(c, chunk, lines);
                loadPayments(c, chunk, payments);
            }
            List<SaleRecord> result = new ArrayList<>();
            for (Map.Entry<String, Object[]> e : heads.entrySet()) {
                Object[] h = e.getValue();
                result.add(new SaleRecord(e.getKey(), (String) h[0], (String) h[1], (String) h[2], (Instant) h[3],
                        lines.getOrDefault(e.getKey(), List.of()), payments.getOrDefault(e.getKey(), List.of()),
                        (BigDecimal) h[4], (BigDecimal) h[5], (BigDecimal) h[6], (BigDecimal) h[7]));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Could not read sales", e);
        }
    }

    private static void loadLines(Connection c, List<String> ids, Map<String, List<SaleLineRecord>> into)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT SaleID, LineNo, ProductID, Barcode, ProductName, Unit, Quantity, UnitPrice, PriceType, "
                        + "Vatable, LineTotal FROM SaleLine WHERE SaleID IN (" + marks(ids.size()) + ") "
                        + "ORDER BY SaleID, LineNo")) {
            bind(ps, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.computeIfAbsent(rs.getString("SaleID"), k -> new ArrayList<>()).add(new SaleLineRecord(
                            rs.getInt("LineNo"), rs.getString("ProductID"), rs.getString("Barcode"),
                            rs.getString("ProductName"), rs.getString("Unit"), rs.getBigDecimal("Quantity"),
                            rs.getBigDecimal("UnitPrice"), PriceType.valueOf(rs.getString("PriceType")),
                            rs.getBoolean("Vatable"), rs.getBigDecimal("LineTotal")));
                }
            }
        }
    }

    private static void loadPayments(Connection c, List<String> ids, Map<String, List<PaymentRecord>> into)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT SaleID, PaymentNo, Method, ReferenceNo, AmountTendered, AmountApplied, ChangeGiven "
                        + "FROM Payment WHERE SaleID IN (" + marks(ids.size()) + ") ORDER BY SaleID, PaymentNo")) {
            bind(ps, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    into.computeIfAbsent(rs.getString("SaleID"), k -> new ArrayList<>()).add(new PaymentRecord(
                            rs.getInt("PaymentNo"), PaymentMethod.valueOf(rs.getString("Method")),
                            rs.getString("ReferenceNo"), rs.getBigDecimal("AmountTendered"),
                            rs.getBigDecimal("AmountApplied"), rs.getBigDecimal("ChangeGiven")));
                }
            }
        }
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private static void bind(PreparedStatement ps, List<String> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) {
            ps.setString(i + 1, ids.get(i));
        }
    }

    /* UTC wall-clock time in DATETIME: no time-zone conversion by the driver either way. */

    static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(instant, "time"), ZoneOffset.UTC);
    }

    static Instant fromDb(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }
}
