package inventory.contract;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * CONTRACT — implemented by the Inventory module, called by POS.
 *
 * When a sale is completed, POS calls {@link #recordSale} INSIDE the same
 * database transaction that saves the sale (Database.inTransaction). So either
 * the sale and the stock-out are both saved, or neither is.
 *
 * Inventory does not know about POS types: POS hands over plain
 * {@link StockLine}s.
 */
public interface StockRecorder {

    /**
     * Deducts every line in {@code lines} from stock.
     *
     * Implementations must:
     * - use the given {@code tx} connection, and never commit, roll back or close it;
     * - check stock again (another terminal may have sold the item since it
     *   was added to the cart) and throw {@link InsufficientStockException}
     *   naming the first product that is short — POS then cancels the whole sale.
     *
     * @param reference what caused the stock-out (POS passes the invoice number)
     * @param lines     what to deduct; the same product may appear more than once
     * @throws InsufficientStockException a line asks for more than is in stock
     * @throws SQLException               database problem (the sale is rolled back)
     */
    void recordSale(String reference, List<StockLine> lines, Connection tx)
            throws InsufficientStockException, SQLException;
}
