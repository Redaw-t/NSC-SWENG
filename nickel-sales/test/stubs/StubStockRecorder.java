package stubs;

import inventory.contract.InsufficientStockException;
import inventory.contract.StockLine;
import inventory.contract.StockRecorder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TEST STAND-IN for the Inventory module's StockRecorder. Inside the sale's own transaction it
 * re-checks and deducts Inventory.QuantityOnHand (the source of truth for stock). It writes no
 * stock-movement history: the real InventoryService does that.
 */
public class StubStockRecorder implements StockRecorder {

    @Override
    public void recordSale(String reference, List<StockLine> lines, Connection tx)
            throws InsufficientStockException, SQLException {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (StockLine line : lines) {
            totals.merge(line.productId(), line.quantity(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            try (PreparedStatement read = tx.prepareStatement(
                    "SELECT p.ProductName, COALESCE(i.QuantityOnHand, 0) AS StockOnHand "
                            + "FROM Product p LEFT JOIN Inventory i ON i.ProductID = p.ProductID "
                            + "WHERE p.ProductID = ? FOR UPDATE")) {
                read.setString(1, entry.getKey());
                try (ResultSet rs = read.executeQuery()) {
                    if (!rs.next()) {
                        throw new InsufficientStockException(entry.getKey(), entry.getKey(),
                                BigDecimal.ZERO, entry.getValue());
                    }
                    BigDecimal onHand = rs.getBigDecimal("StockOnHand");
                    if (onHand.compareTo(entry.getValue()) < 0) {
                        throw new InsufficientStockException(entry.getKey(), rs.getString("ProductName"),
                                onHand, entry.getValue());
                    }
                }
            }
            try (PreparedStatement deduct = tx.prepareStatement(
                    "UPDATE Inventory SET QuantityOnHand = QuantityOnHand - ? WHERE ProductID = ?")) {
                deduct.setBigDecimal(1, entry.getValue());
                deduct.setString(2, entry.getKey());
                deduct.executeUpdate();
            }
        }
    }

    @Override
    public void recordReturn(String reference, List<StockLine> lines, Connection tx) throws SQLException {
        for (StockLine line : lines) {
            try (PreparedStatement add = tx.prepareStatement(
                    "UPDATE Inventory SET QuantityOnHand = QuantityOnHand + ? WHERE ProductID = ?")) {
                add.setBigDecimal(1, line.quantity());
                add.setString(2, line.productId());
                add.executeUpdate();
            }
        }
    }
}
