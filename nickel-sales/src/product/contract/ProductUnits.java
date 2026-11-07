package product.contract;

import java.util.List;

/**
 * The units a product can be sold in. The Add / Edit Product form and the POS Open Item dialog
 * both offer {@link #CHOICES} in their unit drop-down (with an "Others" field for anything else),
 * so the two always match. Change the list here to change both.
 */
public final class ProductUnits {

    /** The units offered in the drop-downs, first = the default. */
    public static final List<String> CHOICES = List.of("pc", "box", "ream", "pad");

    /** The default unit for a new product or open item. */
    public static final String DEFAULT = CHOICES.get(0);

    /** Longest unit (Product.Unit and SaleLine.Unit are VARCHAR(10)). */
    public static final int MAX_LENGTH = 10;

    private ProductUnits() { }
}
