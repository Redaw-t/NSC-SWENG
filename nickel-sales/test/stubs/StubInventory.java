package stubs;

import shared.Money;
import shared.Quantity;
import inventory.contract.InsufficientStockException;
import inventory.contract.StockLine;
import inventory.contract.StockRecorder;
import product.contract.ProductInfo;
import product.contract.ProductLookup;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * TEST STAND-IN for the Product and Inventory modules: an in-memory catalog
 * with stock, implementing ProductLookup and StockRecorder. Lets POS be built
 * and tested before the real modules exist. Production code under src/ must
 * never use it.
 *
 * The sample products cover the cases POS must handle:
 *
 * | Barcode       | Product               | Retail | Wholesale | Notes                    |
 * | 4800000000019 | Bond Paper A4 (ream)  | 250.00 | 235.00    |                          |
 * | 4800000000026 | Ballpen Black         |  12.00 |  10.00    |                          |
 * | 4800000000033 | Notebook 80 leaves    |  45.00 |  (none)   | no wholesale price       |
 * | 4800000000040 | Rice (per kg)         |  52.50 |  50.00    | VAT-exempt, sold by kg   |
 * | 4800000000057 | Masking Tape 1 inch   |  35.75 |  32.00    | out of stock             |
 * | 4800000000064 | Desk Calculator       | 499.00 |  (none)   | inactive (no longer sold)|
 * | (no barcode)  | Glue Stick            |  18.00 |  16.50    | find by search only      |
 */
public final class StubInventory implements ProductLookup, StockRecorder {

    private final Map<String, ProductInfo> products = new LinkedHashMap<>();

    public StubInventory() {
        add("P-0001", "4800000000019", "Bond Paper A4 (ream)", "ream", "250.00", "235.00", true, true, "40");
        add("P-0002", "4800000000026", "Ballpen Black", "pc", "12.00", "10.00", true, true, "200");
        add("P-0003", "4800000000033", "Notebook 80 leaves", "pc", "45.00", null, true, true, "60");
        add("P-0004", "4800000000040", "Rice (per kg)", "kg", "52.50", "50.00", false, true, "100.5");
        add("P-0005", "4800000000057", "Masking Tape 1 inch", "roll", "35.75", "32.00", true, true, "0");
        add("P-0006", "4800000000064", "Desk Calculator", "pc", "499.00", null, true, false, "5");
        add("P-0007", null, "Glue Stick", "pc", "18.00", "16.50", true, true, "30");
    }

    /** Adds or replaces a product (tests use this to set up special cases). */
    public synchronized void add(String id, String barcode, String name, String unit, String retail,
                                 String wholesale, boolean vatable, boolean active, String stock) {
        products.put(id, new ProductInfo(id, barcode, name, unit, Money.of(retail),
                wholesale == null ? null : Money.of(wholesale), vatable, active, Quantity.of(stock)));
    }

    /** Current stock of a product (for tests). */
    public synchronized BigDecimal stockOf(String productId) {
        return products.get(productId).stockOnHand();
    }

    @Override
    public synchronized Optional<ProductInfo> findByBarcode(String barcode) {
        if (barcode == null) {
            return Optional.empty();
        }
        String code = barcode.trim();
        return products.values().stream().filter(p -> code.equals(p.barcode())).findFirst();
    }

    @Override
    public synchronized Optional<ProductInfo> findById(String productId) {
        return Optional.ofNullable(productId == null ? null : products.get(productId.trim()));
    }

    @Override
    public synchronized List<ProductInfo> search(String text, int limit) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return products.values().stream()
                .filter(ProductInfo::active)
                .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(needle)
                        || (p.barcode() != null && p.barcode().contains(needle)))
                .sorted(Comparator.comparing(ProductInfo::name, String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * All-or-nothing like the real one: checks every line first (adding up
     * lines of the same product), and only then deducts. The connection is
     * ignored because nothing here is in the database.
     */
    @Override
    public synchronized void recordSale(String reference, List<StockLine> lines, Connection tx)
            throws InsufficientStockException {
        Map<String, BigDecimal> needed = new LinkedHashMap<>();
        for (StockLine line : lines) {
            needed.merge(line.productId(), line.quantity(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : needed.entrySet()) {
            ProductInfo p = products.get(e.getKey());
            BigDecimal available = p == null ? BigDecimal.ZERO : p.stockOnHand();
            if (available.compareTo(e.getValue()) < 0) {
                String name = p == null ? e.getKey() : p.name();
                throw new InsufficientStockException(e.getKey(), name, available, e.getValue());
            }
        }
        for (Map.Entry<String, BigDecimal> e : needed.entrySet()) {
            ProductInfo p = products.get(e.getKey());
            products.put(p.productId(), new ProductInfo(p.productId(), p.barcode(), p.name(), p.unit(),
                    p.retailPrice(), p.wholesalePrice(), p.vatable(), p.active(),
                    p.stockOnHand().subtract(e.getValue())));
        }
    }

    /** All products, including inactive ones (for tests). */
    public synchronized List<ProductInfo> all() {
        return new ArrayList<>(products.values());
    }
}
