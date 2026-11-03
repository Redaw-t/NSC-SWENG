package stubs;

import auth.permission.AuthorizationService;
import product.repository.MySqlCategoryRepository;
import product.repository.MySqlProductRepository;
import product.repository.MySqlSupplierRepository;
import product.service.ProductService;
import shared.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * TEST SUPPORT: the real product catalog (ProductService on MySQL) plus what the
 * self-tests need to use it as a fixture (it replaced the old in-memory StubInventory).
 *
 * The checks that use it assume the nine seed products of
 * database/NickelSales-Database-Config.sql (P-0001 to P-0009). {@link #reset()}
 * puts exactly those nine back to their seed values (name, barcode, prices, VAT flag,
 * active flag, stock) before every scenario, so a sale or an edit made by one check
 * never leaks into the next. Run the checks on a development database, not a live one.
 * Any other product is left alone, apart from the throw-away P-0099.
 *
 * Stock lives in the Inventory table (Inventory.QuantityOnHand): that is what
 * ProductLookup reports as ProductInfo.stockOnHand and what the Inventory module
 * deducts at checkout. {@link #setStock} and {@link #stockOf} use that table.
 * The StockMovement history is never touched here.
 */
public final class TestCatalog {

    /** The seed products, exactly as the SQL script inserts them. */
    private static final Object[][] SEED = {
            {"P-0001", "4800000000019", "Bond Paper A4 (ream)", "ream", "250.00", "235.00", true, true, "40"},
            {"P-0002", "4800000000026", "Ballpen Black", "pc", "12.00", "10.00", true, true, "200"},
            {"P-0003", "4800000000033", "Notebook 80 leaves", "pc", "45.00", null, true, true, "60"},
            {"P-0004", "4800000000040", "Rice (per kg)", "kg", "52.50", "50.00", false, true, "100.5"},
            {"P-0005", "4800000000057", "Masking Tape 1 inch", "roll", "35.75", "32.00", true, true, "0"},
            {"P-0006", "4800000000064", "Desk Calculator", "pc", "499.00", null, true, false, "5"},
            {"P-0007", null, "Glue Stick", "pc", "18.00", "16.50", true, true, "30"},
            {"P-0008", "6970340973798", "Osmanthus Oolong Tea", "box", "285.00", "255.00", true, true, "10"},
            {"P-0009", "4800067130063", "Cleene Ethyl Alcohol", "pc", "98.00", "76.00", true, true, "10"}};

    /** The only extra product a check may add (see {@link #addTestProduct}); reset() removes it. */
    public static final String THROWAWAY_ID = "P-0099";

    /**
     * Sets a product's stock, creating its Inventory row if it has none. Times are UTC, like
     * MySqlInventoryRepository; an existing row keeps its reorder level.
     * Quantity first, then the product ID: matches {@link #update(String, Object, String)}.
     */
    private static final String STOCK_UPSERT =
            "INSERT INTO Inventory (QuantityOnHand, ProductID, ReorderLevel, UpdatedAt) "
                    + "VALUES (?, ?, 0, UTC_TIMESTAMP(3)) "
                    + "ON DUPLICATE KEY UPDATE QuantityOnHand = VALUES(QuantityOnHand), UpdatedAt = VALUES(UpdatedAt)";

    private TestCatalog() { }

    /** The real product service, with no audit log (the checks do not need one). */
    public static ProductService products() {
        return new ProductService(new MySqlProductRepository(), new MySqlCategoryRepository(),
                new MySqlSupplierRepository(), new AuthorizationService(), null);
    }

    private static Boolean reachable;

    /** True when MySQL can be reached (asked once); checks that need products are skipped when it is not. */
    public static synchronized boolean available() {
        if (reachable == null) {
            try (Connection c = Database.getConnection()) {
                reachable = c.isValid(2);
            } catch (SQLException e) {
                reachable = false;
            }
        }
        return reachable;
    }

    /** {@link #reset()} when MySQL is reachable, nothing otherwise. The checks call this around every scenario. */
    public static void resetIfAvailable() {
        if (available()) {
            reset();
        }
    }

    /**
     * Puts the nine seed products back to their seed values, including their stock in the
     * Inventory table, and removes the throw-away product. All in one transaction.
     */
    public static void reset() {
        // TODO remove StockOnHand here when Product.StockOnHand is retired; ProductCheck still reads it.
        String upsert = "INSERT INTO Product (ProductID, Barcode, ProductName, Unit, RetailPrice, WholesalePrice, "
                + "Vatable, IsActive, StockOnHand) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE Barcode = VALUES(Barcode), ProductName = VALUES(ProductName), "
                + "Unit = VALUES(Unit), RetailPrice = VALUES(RetailPrice), WholesalePrice = VALUES(WholesalePrice), "
                + "Vatable = VALUES(Vatable), IsActive = VALUES(IsActive), StockOnHand = VALUES(StockOnHand)";
        try {
            Database.inTransaction(tx -> {
                try (PreparedStatement drop = tx.prepareStatement("DELETE FROM Product WHERE ProductID = ?")) {
                    drop.setString(1, THROWAWAY_ID);
                    drop.executeUpdate();
                }
                try (PreparedStatement drop = tx.prepareStatement("DELETE FROM Inventory WHERE ProductID = ?")) {
                    drop.setString(1, THROWAWAY_ID);
                    drop.executeUpdate();
                }
                try (PreparedStatement ps = tx.prepareStatement(upsert)) {
                    for (Object[] p : SEED) {
                        ps.setString(1, (String) p[0]);
                        ps.setString(2, (String) p[1]);
                        ps.setString(3, (String) p[2]);
                        ps.setString(4, (String) p[3]);
                        ps.setBigDecimal(5, new BigDecimal((String) p[4]));
                        ps.setBigDecimal(6, p[5] == null ? null : new BigDecimal((String) p[5]));
                        ps.setBoolean(7, (Boolean) p[6]);
                        ps.setBoolean(8, (Boolean) p[7]);
                        ps.setBigDecimal(9, new BigDecimal((String) p[8]));
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = tx.prepareStatement(STOCK_UPSERT)) {
                    for (Object[] p : SEED) {
                        ps.setBigDecimal(1, new BigDecimal((String) p[8]));   // quantity first
                        ps.setString(2, (String) p[0]);                       // then the product ID
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new RuntimeException("Could not reset the seed products (is the database set up?)", e);
        }
    }

    /** What the Inventory module does: sets a product's stock directly (e.g. "sold at another terminal"). */
    public static void setStock(String productId, String quantity) {
        update(STOCK_UPSERT, new BigDecimal(quantity), productId);
    }

    /** Discontinues or reactivates a product directly. */
    public static void setActive(String productId, boolean active) {
        update("UPDATE Product SET IsActive = ? WHERE ProductID = ?", active, productId);
    }

    /** Adds the throw-away product P-0099 with its stock for one scenario (removed by the next {@link #reset()}). */
    public static void addTestProduct(String barcode, String name, String unit, String retail,
                                      String wholesale, boolean vatable, boolean active, String stock) {
        // TODO remove StockOnHand here when Product.StockOnHand is retired; ProductCheck still reads it.
        String sql = "INSERT INTO Product (ProductID, Barcode, ProductName, Unit, RetailPrice, WholesalePrice, "
                + "Vatable, IsActive, StockOnHand) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, THROWAWAY_ID);
            ps.setString(2, barcode);
            ps.setString(3, name);
            ps.setString(4, unit);
            ps.setBigDecimal(5, new BigDecimal(retail));
            ps.setBigDecimal(6, wholesale == null ? null : new BigDecimal(wholesale));
            ps.setBoolean(7, vatable);
            ps.setBoolean(8, active);
            ps.setBigDecimal(9, new BigDecimal(stock));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not add the test product", e);
        }
        update(STOCK_UPSERT, new BigDecimal(stock), THROWAWAY_ID);
    }

    /** Current stock of a product, read straight from the Inventory table (0.000 if it has no row). */
    public static BigDecimal stockOf(String productId) {
        try (Connection c = Database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT QuantityOnHand FROM Inventory WHERE ProductID = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO.setScale(3);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Could not read the stock of " + productId, e);
        }
    }

    private static void update(String sql, Object value, String productId) {
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, value);
            ps.setString(2, productId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not update " + productId, e);
        }
    }
}
