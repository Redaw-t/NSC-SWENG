package barcode.service;

import product.contract.ProductInfo;
import product.contract.ProductLookup;

import java.util.List;
import java.util.Objects;

/**
 * Barcode use cases that need no write access to products: find products with no barcode,
 * suggest a new unused code, and print labels. Saving a code on a product comes next, through
 * a contract agreed with the Product owner.
 *
 * Note: ProductLookup.search returns active products only, which is what we want here.
 */
public class BarcodeService {

    private static final int SEARCH_LIMIT = 10_000;

    private final ProductLookup products;
    private final BarcodeGenerator generator;
    private final LabelPrinterPort printer;

    public BarcodeService(ProductLookup products, LabelPrinterPort printer) {
        this.products = Objects.requireNonNull(products, "products");
        this.printer = Objects.requireNonNull(printer, "printer");
        this.generator = new BarcodeGenerator(products);
    }

    /** Sold products that have no barcode yet, sorted by name. */
    public List<ProductInfo> productsWithoutBarcode() {
        return products.search("", SEARCH_LIMIT).stream()
                .filter(p -> p.barcode() == null || p.barcode().isBlank())
                .toList();
    }

    /** Sold products that already have a barcode, sorted by name. */
    public List<ProductInfo> productsWithBarcode() {
        return products.search("", SEARCH_LIMIT).stream()
                .filter(p -> p.barcode() != null && !p.barcode().isBlank())
                .toList();
    }

    /** A new unused barcode (not saved anywhere yet). */
    public String suggestBarcode() {
        return generator.next();
    }

    /** Prints labels for a product that has a barcode. */
    public void printLabels(ProductInfo product, int copies) throws LabelPrintException {
        Objects.requireNonNull(product, "product");
        if (product.barcode() == null || product.barcode().isBlank()) {
            throw new LabelPrintException(product.name() + " has no barcode yet. Generate one first.");
        }
        printer.printLabel(product.name(), product.barcode(), copies);
    }
}
