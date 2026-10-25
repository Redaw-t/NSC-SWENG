package pos.contract;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * CONTRACT — implemented by the Product &amp; Inventory module, called by POS.
 *
 * When a sale is completed, POS calls {@link #recordSale} INSIDE the same
 * database transaction that saves the sale (Database.inTransaction). So either
 * the sale and the stock-out are both saved, or neither is.
 */
public interface StockRecorder {

    /**
     * Deducts every line of {@code sale} from stock.
     *
     * Implementations must:
     * - use the given {@code tx} connection, and never commit, roll back or close it;
     * - check stock again (another terminal may have sold the item since it
     *   was added to the cart) and throw {@link InsufficientStockException}
     *   naming the first product that is short — POS then cancels the whole sale.
     *
     * @throws InsufficientStockException a line asks for more than is in stock
     * @throws SQLException               database problem (the sale is rolled back)
     */
    void recordSale(SaleRecord sale, Connection tx) throws InsufficientStockException, SQLException;
}
