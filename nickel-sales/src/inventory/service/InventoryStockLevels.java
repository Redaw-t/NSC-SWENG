package inventory.service;

import inventory.contract.StockLevels;
import inventory.model.StockLevel;
import inventory.repository.InventoryRepository;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Implements the read-only {@link StockLevels} contract.
 *
 * Deliberately NOT part of InventoryService: InventoryService needs the Product
 * module's ProductLookup (to name products), and the Product module needs
 * StockLevels (to fill ProductInfo.stockOnHand). Keeping this class free of
 * ProductLookup avoids a construction cycle in Main.
 */
public class InventoryStockLevels implements StockLevels {

    private final InventoryRepository repository;

    public InventoryStockLevels(InventoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public BigDecimal available(String productId) {
        if (productId == null) {
            return BigDecimal.ZERO;
        }
        return repository.find(productId.trim())
                .map(StockLevel::quantityOnHand)
                .orElse(BigDecimal.ZERO);
    }
}
