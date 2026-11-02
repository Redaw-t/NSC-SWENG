package stubs;

import inventory.model.StockLevel;
import inventory.model.StockMovement;
import inventory.repository.InventoryRepository;
import shared.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * TEST STAND-IN for {@code MySqlInventoryRepository}: keeps stock and the
 * movement history in memory, so the Inventory rules can be tested with no
 * database. Production code under src/ must never use it.
 *
 * {@link #inTransaction} behaves like the real one: if the work throws,
 * everything it changed is put back. The {@code tx} connection is always null.
 */
public final class InMemoryInventoryRepository implements InventoryRepository {

    private final Map<String, StockLevel> levels = new TreeMap<>();
    private final List<StockMovement> movements = new ArrayList<>();

    @Override
    public synchronized Optional<StockLevel> find(String productId) {
        return Optional.ofNullable(levels.get(productId));
    }

    @Override
    public synchronized List<StockLevel> findAll() {
        return new ArrayList<>(levels.values());
    }

    @Override
    public synchronized List<StockMovement> findMovements(String productId, int limit) {
        List<StockMovement> out = new ArrayList<>();
        for (int i = movements.size() - 1; i >= 0 && out.size() < limit; i--) {
            if (productId == null || movements.get(i).productId().equals(productId)) {
                out.add(movements.get(i));
            }
        }
        return out;
    }

    @Override
    public synchronized <T, E extends Exception> T inTransaction(Database.TransactionWork<T, E> work) throws E {
        Map<String, StockLevel> levelsBefore = new TreeMap<>(levels);
        List<StockMovement> movementsBefore = new ArrayList<>(movements);
        try {
            return work.run(null);
        } catch (SQLException e) {
            restore(levelsBefore, movementsBefore);
            throw new RuntimeException(e.getMessage(), e);
        } catch (Throwable t) {
            restore(levelsBefore, movementsBefore);
            throw t;
        }
    }

    private void restore(Map<String, StockLevel> levelsBefore, List<StockMovement> movementsBefore) {
        levels.clear();
        levels.putAll(levelsBefore);
        movements.clear();
        movements.addAll(movementsBefore);
    }

    @Override
    public synchronized Optional<StockLevel> lock(Connection tx, String productId) {
        return Optional.ofNullable(levels.get(productId));
    }

    @Override
    public synchronized StockLevel lockOrCreate(Connection tx, String productId, Instant now) {
        return levels.computeIfAbsent(productId,
                id -> new StockLevel(id, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, now));
    }

    @Override
    public synchronized void update(Connection tx, StockLevel level) throws SQLException {
        if (!levels.containsKey(level.productId())) {
            throw new SQLException("Inventory row missing for " + level.productId());
        }
        levels.put(level.productId(), level);
    }

    @Override
    public synchronized void addMovement(Connection tx, StockMovement movement) {
        movements.add(movement);
    }

    /** Every movement so far, oldest first (for tests). */
    public synchronized List<StockMovement> movements() {
        return new ArrayList<>(movements);
    }

    /** True when the product has a stock row at all (for tests). */
    public synchronized boolean hasRow(String productId) {
        return levels.containsKey(productId);
    }
}
