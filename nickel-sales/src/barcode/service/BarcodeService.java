package barcode.service;

import product.contract.BarcodeAssignException;
import product.contract.BarcodeAssigner;
import product.contract.BarcodeEntry;
import product.contract.ProductInfo;
import product.contract.ProductLookup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Barcode use cases: list every sold product with its barcode status, suggest a new unused code,
 * check and save a barcode on a product, and print labels. Products are listed and saved only
 * through the Product module's contracts ({@link ProductLookup}, {@link BarcodeAssigner}).
 *
 * Note: only active products are listed, which is what we want here.
 */
public class BarcodeService {

    private final ProductLookup products;
    private final BarcodeAssigner assigner;
    private final BarcodeGenerator generator;
    private final LabelPrinterPort printer;

    public BarcodeService(ProductLookup products, BarcodeAssigner assigner, LabelPrinterPort printer) {
        this.products = Objects.requireNonNull(products, "products");
        this.assigner = Objects.requireNonNull(assigner, "assigner");
        this.printer = Objects.requireNonNull(printer, "printer");
        this.generator = new BarcodeGenerator(products);
    }

    /** Every sold product, sorted by name, each with its status (assigned, unassigned or error). */
    public List<BarcodeRow> rows() throws BarcodeAssignException {
        List<BarcodeEntry> entries = assigner.listEntries();

        Map<String, Integer> uses = new HashMap<>();
        for (BarcodeEntry e : entries) {
            String code = key(e.product().barcode());
            if (code != null) {
                uses.merge(code, 1, Integer::sum);
            }
        }

        List<BarcodeRow> rows = new ArrayList<>();
        for (BarcodeEntry e : entries) {
            ProductInfo p = e.product();
            String code = key(p.barcode());
            if (code == null) {
                rows.add(new BarcodeRow(p, BarcodeStatus.UNASSIGNED, null, e.lastUpdated()));
                continue;
            }
            String problem = BarcodeValidator.problemWith(p.barcode());
            if (problem == null && uses.get(code) > 1) {
                problem = "Another product has the same barcode.";
            }
            rows.add(new BarcodeRow(p, problem == null ? BarcodeStatus.ASSIGNED : BarcodeStatus.ERROR,
                    problem, e.lastUpdated()));
        }
        return rows;
    }

    /** A new unused barcode (not saved anywhere yet). */
    public String suggestBarcode() {
        return generator.next();
    }

    /**
     * What is wrong with {@code barcode} for this product, ready to show, or null when it can be saved:
     * the format rules, and "already used by ..." when another product has it. Checked again when saving.
     */
    public String problemWith(String productId, String barcode) {
        String problem = BarcodeValidator.problemWith(barcode);
        if (problem != null) {
            return problem;
        }
        return products.findByBarcode(barcode.trim())
                .filter(other -> !other.productId().equals(productId))
                .map(other -> "That barcode is already used by \"" + other.name() + "\" (" + other.productId() + ").")
                .orElse(null);
    }

    /**
     * Checks and saves the barcode on the product, replacing the one it has.
     *
     * @throws BarcodeAssignException the message says what to fix (wrong check digit, used by another product, no permission, ...)
     */
    public void assign(String productId, String barcode) throws BarcodeAssignException {
        Objects.requireNonNull(productId, "productId");
        String problem = problemWith(productId, barcode);
        if (problem != null) {
            throw new BarcodeAssignException(problem);
        }
        assigner.assignBarcode(productId, barcode.trim());
    }

    /** Prints labels for a product that has a barcode, with both prices. */
    public void printLabels(ProductInfo product, int copies) throws LabelPrintException {
        printLabels(product, copies, true, true);
    }

    /** Prints labels with only the ticked prices (retail, wholesale, both or none). */
    public void printLabels(ProductInfo product, int copies, boolean showRetail, boolean showWholesale)
            throws LabelPrintException {
        Objects.requireNonNull(product, "product");
        if (product.barcode() == null || product.barcode().isBlank()) {
            throw new LabelPrintException(product.name() + " has no barcode yet. Generate one first.");
        }
        printer.printLabel(LabelContent.of(product, showRetail, showWholesale), copies);
    }

    /** The label exactly as {@link #printLabels} prints it, for the preview; null if it has no barcode. */
    public LabelBitmap previewLabel(ProductInfo product, boolean showRetail, boolean showWholesale) {
        Objects.requireNonNull(product, "product");
        if (product.barcode() == null || product.barcode().isBlank()) {
            return null;
        }
        return printer.render(LabelContent.of(product, showRetail, showWholesale));
    }

    private static String key(String barcode) {
        return barcode == null || barcode.isBlank() ? null : barcode.trim().toLowerCase(Locale.ROOT);
    }
}
