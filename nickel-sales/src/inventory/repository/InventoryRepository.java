package inventory.repository;

import inventory.model.StockLevel;
import inventory.model.StockMovement;
import shared.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * What the Inventory service needs stored and loaded. All the SQL lives in
 * {@link MySqlInventoryRepository}; the service never sees it.
 *
 * Writes take the {@code tx} connection of a running transaction and never
 * commit, roll back or close it. Start one with {@link #inTransaction}; POS
 * checkout hands its own transaction to {@code InventoryService.recordSale}.
 *
 * Database problems from the methods that open their own connection are
 * reported as RuntimeException (like MySqlUserRepository).
 */
public interface InventoryRepository {

    /** Current stock of a product, or empty if it has never been stocked. */
    Optional<StockLevel> find(String productId);

    /**
     * Runs {@code work} as one all-or-nothing transaction: committed together,
     * or rolled back if it throws anything. Same contract as
     * {@link Database#inTransaction}, except that a database failure comes out
     * as RuntimeException so services stay free of SQL types.
     */
    <T, E extends Exception> T inTransaction(Database.TransactionWork<T, E> work) throws E;

    /** Locks the product's stock row until the transaction ends; empty if it has none. */
    Optional<StockLevel> lock(Connection tx, String productId) throws SQLException;

    /** Like {@link #lock}, but first creates an empty row (0 on hand, reorder level 0) when missing. */
    StockLevel lockOrCreate(Connection tx, String productId, Instant now) throws SQLException;

    /** Saves the level's quantity, reorder level and update time. The row must exist. */
    void update(Connection tx, StockLevel level) throws SQLException;

    /** Appends a line to the movement history. */
    void addMovement(Connection tx, StockMovement movement) throws SQLException;
}
