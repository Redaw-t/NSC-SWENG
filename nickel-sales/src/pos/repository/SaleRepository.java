package pos.repository;

import pos.contract.SaleHistory;
import pos.contract.SaleRecord;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Where completed sales are kept (SaleRepository in the class diagram).
 * POS saves through it; other modules only READ, through the
 * {@link SaleHistory} part (and Sync through SaleExport).
 * The real one is {@link MySqlSaleRepository}.
 */
public interface SaleRepository extends SaleHistory {

    /**
     * Saves a completed sale with its lines and payments, using the sale's
     * transaction connection (never commits, rolls back or closes it).
     * A saved sale is never changed afterwards.
     */
    void save(SaleRecord sale, Connection tx) throws SQLException;
}
