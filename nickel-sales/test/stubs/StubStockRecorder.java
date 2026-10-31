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
 * TEST STAND-IN for the Inventory module's StockRecorder (delete it when the real
 * one exists). It does what the contract asks, in the simplest way: inside the
 * sale's own transaction it re-checks and deducts {@code Product.StockOnHand}.
 *
 * It works on the REAL products in MySQL, so after a sale in DevApp the stock
 * really goes down. It writes no stock-movement history: that is
 * the real Inventory module's job. The real recorder can copy the two statements
 * below as its starting point.
 */
public class StubStockRecorder implements StockRecorder {

    @Override
    public void recordSale(String reference, List<StockLine> lines, Connection tx)
            throws InsufficientStockException, SQLException {
        // The same product may appear on several lines: deduct it once, in total.
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (StockLine line : lines) {
            totals.merge(line.productId(), line.quantity(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            // FOR UPDATE: another terminal selling the same product waits for this sale to finish.
            try (PreparedStatement read = tx.prepareStatement(
                    "SELECT ProductName, StockOnHand FROM Product WHERE ProductID = ? FOR UPDATE")) {
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
                    "UPDATE Product SET StockOnHand = StockOnHand - ? WHERE ProductID = ?")) {
                deduct.setBigDecimal(1, entry.getValue());
                deduct.setString(2, entry.getKey());
                deduct.executeUpdate();
            }
        }
    }
}
