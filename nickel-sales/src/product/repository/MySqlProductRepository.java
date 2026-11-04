
package product.repository;

import product.model.Product;
import shared.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ProductRepository backed by the Product table (with the category and supplier
 * names joined in).
 *
 * Stock is read from the Inventory table, which is the source of truth for
 * stock quantities. Product.StockOnHand is no longer used for displaying stock.
 */
public class MySqlProductRepository implements ProductRepository {

    private static final String SELECT =
            "SELECT p.ProductID, p.Barcode, p.ProductName, p.Unit, p.RetailPrice, p.WholesalePrice, "
                    + "p.Vatable, p.IsActive, p.CategoryID, c.CategoryName, p.SupplierID, s.SupplierName, "
                    + "COALESCE(i.QuantityOnHand, 0) AS StockOnHand "
                    + "FROM Product p "
                    + "LEFT JOIN Category c ON c.CategoryID = p.CategoryID "
                    + "LEFT JOIN Supplier s ON s.SupplierID = p.SupplierID "
                    + "LEFT JOIN Inventory i ON i.ProductID = p.ProductID";

    /** Marks a LIKE pattern's own % and _ as plain characters. */
    private static final char LIKE_ESCAPE = '!';

    @Override
    public Optional<Product> findById(String productId) {
        if (productId == null) {
            return Optional.empty();
        }
        return queryOne(SELECT + " WHERE p.ProductID = ?", productId.trim());
    }

    @Override
    public Optional<Product> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        return queryOne(SELECT + " WHERE p.Barcode = ?", barcode.trim());
    }

    @Override
    public List<Product> search(String text, boolean activeOnly, int limit) {
        String needle = text == null ? "" : text.trim();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");

        if (activeOnly) {
            sql.append(" AND p.IsActive = TRUE");
        }

        if (!needle.isEmpty()) {
            sql.append(" AND (p.ProductName LIKE ? ESCAPE '").append(LIKE_ESCAPE)
                    .append("' OR p.Barcode LIKE ? ESCAPE '").append(LIKE_ESCAPE).append("')");
        }

        sql.append(" ORDER BY p.ProductName, p.ProductID LIMIT ?");

        List<Product> found = new ArrayList<>();

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {

            int i = 1;

            if (!needle.isEmpty()) {
                String pattern = "%" + escapeLike(needle) + "%";
                ps.setString(i++, pattern);
                ps.setString(i++, pattern);
            }

            ps.setInt(i, Math.max(0, limit));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(map(rs));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("Could not search products", e);
        }

        return found;
    }

    @Override
    public String nextProductId() {
        String sql = "SELECT COALESCE(MAX(CAST(SUBSTRING(ProductID, 3) AS UNSIGNED)), 0) "
                + "FROM Product WHERE ProductID REGEXP '^P-[0-9]+$'";

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            rs.next();
            return String.format("P-%04d", rs.getLong(1) + 1);

        } catch (SQLException e) {
            throw new RuntimeException("Could not work out the next product ID", e);
        }
    }

    @Override
    public void insert(Product p) {
        String sql = "INSERT INTO Product (ProductID, Barcode, ProductName, Unit, RetailPrice, WholesalePrice, "
                + "Vatable, IsActive, CategoryID, SupplierID, StockOnHand) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setString(1, p.productId());
            ps.setString(2, p.barcode());
            ps.setString(3, p.name());
            ps.setString(4, p.unit());
            ps.setBigDecimal(5, p.retailPrice());
            ps.setBigDecimal(6, p.wholesalePrice());
            ps.setBoolean(7, p.vatable());
            ps.setBoolean(8, p.active());
            setNullableInt(ps, 9, p.categoryId());
            setNullableInt(ps, 10, p.supplierId());
            ps.setBigDecimal(11, p.stockOnHand());

            ps.executeUpdate();

        } catch (SQLException e) {
            throw MySqlErrors.translate(e, "Could not save product " + p.productId());
        }
    }

    @Override
    public void update(Product p) {
        String sql = "UPDATE Product SET Barcode = ?, ProductName = ?, Unit = ?, RetailPrice = ?, "
                + "WholesalePrice = ?, Vatable = ?, IsActive = ?, CategoryID = ?, SupplierID = ? "
                + "WHERE ProductID = ?";

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setString(1, p.barcode());
            ps.setString(2, p.name());
            ps.setString(3, p.unit());
            ps.setBigDecimal(4, p.retailPrice());
            ps.setBigDecimal(5, p.wholesalePrice());
            ps.setBoolean(6, p.vatable());
            ps.setBoolean(7, p.active());
            setNullableInt(ps, 8, p.categoryId());
            setNullableInt(ps, 9, p.supplierId());
            ps.setString(10, p.productId());

            if (ps.executeUpdate() == 0) {
                throw new RuntimeException("No such product: " + p.productId());
            }

        } catch (SQLException e) {
            throw MySqlErrors.translate(e, "Could not update product " + p.productId());
        }
    }

    @Override
    public void updateBarcode(String productId, String barcode) {
        String sql = "UPDATE Product SET Barcode = ? WHERE ProductID = ?";

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setString(1, barcode);
            ps.setString(2, productId);

            if (ps.executeUpdate() == 0) {
                throw new RuntimeException("No such product: " + productId);
            }

        } catch (SQLException e) {
            throw MySqlErrors.translate(e, "Could not save the barcode of product " + productId);
        }
    }

    @Override
    public Map<String, LocalDateTime> lastUpdated() {
        Map<String, LocalDateTime> updated = new HashMap<>();

        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT ProductID, UpdatedAt FROM Product");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                // Read as text: UpdatedAt is written by MySQL in its own local time, and the JDBC URL says
                // serverTimezone=UTC, so getTimestamp() would shift it (see MySqlUserRepository.readLocalTime).
                String text = rs.getString("UpdatedAt");
                if (text != null) {
                    updated.put(rs.getString("ProductID"), LocalDateTime.parse(text.trim().replace(' ', 'T')));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("Could not read when products last changed", e);
        }

        return updated;
    }

    private Optional<Product> queryOne(String sql, String param) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setString(1, param);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }

        } catch (SQLException e) {
            throw new RuntimeException("Database error while reading Product", e);
        }
    }

    private static Product map(ResultSet rs) throws SQLException {
        return new Product(
                rs.getString("ProductID"),
                rs.getString("Barcode"),
                rs.getString("ProductName"),
                rs.getString("Unit"),
                rs.getBigDecimal("RetailPrice"),
                rs.getBigDecimal("WholesalePrice"),
                rs.getBoolean("Vatable"),
                rs.getBoolean("IsActive"),
                nullableInt(rs, "CategoryID"),
                rs.getString("CategoryName"),
                nullableInt(rs, "SupplierID"),
                rs.getString("SupplierName"),
                rs.getBigDecimal("StockOnHand"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static String escapeLike(String text) {
        return text.replace(String.valueOf(LIKE_ESCAPE), LIKE_ESCAPE + "" + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }
}
