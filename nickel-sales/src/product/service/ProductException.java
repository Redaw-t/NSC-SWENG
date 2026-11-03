package product.service;

/**
 * A catalog rule was broken (missing name, barcode already used, no permission, ...).
 * Thrown by ProductService, CategoryService and SupplierService;
 * {@link #getMessage()} is safe to show to the user as it is.
 */
public class ProductException extends Exception {

    private static final long serialVersionUID = 1L;

    public ProductException(String message) {
        super(message);
    }
}
