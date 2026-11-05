package product.model;

import product.contract.ProductInfo;
import shared.Money;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A product in the catalog, as the Product module stores it.
 *
 * <p>Other modules never see this class: they get the read-only
 * {@link ProductInfo} (see {@link #toInfo()}). Validation of the product's own
 * fields lives here; rules that need the database (a barcode used by another
 * product) live in the service. Every message thrown is safe to show to a user.
 *
 * @param productId      unique ID, e.g. "P-0001"
 * @param barcode        what a scanner reads; null when the product has none
 * @param name           shown on screen and printed on the receipt
 * @param unit           e.g. "pc", "box", "kg"
 * @param retailPrice    per unit, VAT included
 * @param wholesalePrice per unit, VAT included, once a sale line reaches the wholesale quantity; null = none
 * @param vatable        false = VAT-exempt
 * @param active         false = discontinued (POS will not sell it)
 * @param categoryId     the product's category, or null
 * @param categoryName   display only (filled when read from the database); ignored when saving
 * @param supplierId     the product's supplier, or null
 * @param supplierName   display only (filled when read from the database); ignored when saving
 * @param stockOnHand    current stock. Read here, but written ONLY by the Inventory module
 *                       (stock in / sale stock-out); Product screens and services never change it.
 */
public record Product(
        String productId,
        String barcode,
        String name,
        String unit,
        BigDecimal retailPrice,
        BigDecimal wholesalePrice,
        boolean vatable,
        boolean active,
        Integer categoryId,
        String categoryName,
        Integer supplierId,
        String supplierName,
        BigDecimal stockOnHand) {

    public static final int MAX_ID_LENGTH = 20;
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_UNIT_LENGTH = 10;
    public static final int MAX_BARCODE_LENGTH = 50;
    /** The largest price that fits the DECIMAL(12,2) column. */
    public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    public Product {
        productId = productId == null ? "" : productId.trim();
        if (productId.isEmpty() || productId.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("The product ID must be 1 to " + MAX_ID_LENGTH + " characters.");
        }
        barcode = barcode == null || barcode.isBlank() ? null : barcode.trim();
        if (barcode != null) {
            if (barcode.length() > MAX_BARCODE_LENGTH) {
                throw new IllegalArgumentException("The barcode can be at most " + MAX_BARCODE_LENGTH + " characters.");
            }
            if (barcode.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("The barcode cannot contain spaces.");
            }
        }
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("The product name is required.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The product name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        unit = unit == null ? "" : unit.trim();
        if (unit.isEmpty()) {
            throw new IllegalArgumentException("The unit is required (for example pc, box or kg).");
        }
        if (unit.length() > MAX_UNIT_LENGTH) {
            throw new IllegalArgumentException("The unit can be at most " + MAX_UNIT_LENGTH + " characters.");
        }
        retailPrice = checkedPrice(retailPrice, "retail price", true);
        wholesalePrice = checkedPrice(wholesalePrice, "wholesale price", false);
        stockOnHand = stockOnHand == null ? Quantity.normalize(BigDecimal.ZERO) : Quantity.normalize(stockOnHand);
    }

    private static BigDecimal checkedPrice(BigDecimal price, String label, boolean required) {
        if (price == null) {
            if (required) {
                throw new IllegalArgumentException("The " + label + " is required.");
            }
            return null;
        }
        if (Money.isNegative(price)) {
            throw new IllegalArgumentException("The " + label + " cannot be negative.");
        }
        BigDecimal rounded = Money.round(price);
        if (rounded.compareTo(MAX_PRICE) > 0) {
            throw new IllegalArgumentException("The " + label + " is too large.");
        }
        return rounded;
    }

    /** The read-only view POS uses. */
    public ProductInfo toInfo() {
        return new ProductInfo(productId, barcode, name, unit, retailPrice, wholesalePrice,
                vatable, active, stockOnHand);
    }

    /** The same product, active or discontinued. */
    public Product withActive(boolean newActive) {
        return new Product(productId, barcode, name, unit, retailPrice, wholesalePrice, vatable, newActive,
                categoryId, categoryName, supplierId, supplierName, stockOnHand);
    }

    /** The same product with another barcode (null or blank clears it); the barcode rules are checked. */
    public Product withBarcode(String newBarcode) {
        return new Product(productId, newBarcode, name, unit, retailPrice, wholesalePrice, vatable, active,
                categoryId, categoryName, supplierId, supplierName, stockOnHand);
    }

    /** A new product from a draft (stock starts at zero; Inventory adds stock). */
    public static Product create(String productId, ProductDraft d) {
        Objects.requireNonNull(d, "draft");
        return new Product(productId, d.barcode(), d.name(), d.unit(), d.retailPrice(), d.wholesalePrice(),
                d.vatable(), true, d.categoryId(), null, d.supplierId(), null, BigDecimal.ZERO);
    }

    /** This product with the draft's values; ID, active status and stock are kept. */
    public Product withDraft(ProductDraft d) {
        Objects.requireNonNull(d, "draft");
        return new Product(productId, d.barcode(), d.name(), d.unit(), d.retailPrice(), d.wholesalePrice(),
                d.vatable(), active, d.categoryId(), null, d.supplierId(), null, stockOnHand);
    }
}
