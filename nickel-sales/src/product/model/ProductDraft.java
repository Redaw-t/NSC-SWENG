package product.model;

import java.math.BigDecimal;

/**
 * What the cashier-facing admin types into the add / edit product form.
 * Not validated here: {@link Product} and ProductService check it.
 *
 * @param barcode        blank = no barcode
 * @param wholesalePrice null = no wholesale price
 * @param categoryId     null = no category
 * @param supplierId     null = no supplier
 */
public record ProductDraft(
        String barcode,
        String name,
        String unit,
        BigDecimal retailPrice,
        BigDecimal wholesalePrice,
        boolean vatable,
        Integer categoryId,
        Integer supplierId) {
}
