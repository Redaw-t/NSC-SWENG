package pos.cart;

import pos.checkout.CheckoutException;
import pos.checkout.CheckoutService;
import pos.checkout.InvoiceNumberGenerator;
import pos.contract.InsufficientStockException;
import pos.contract.PaymentRecord;
import pos.contract.ProductInfo;
import pos.contract.ProductLookup;
import pos.contract.SaleRecord;
import shared.Quantity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs the sale on one terminal (SalesService in the class diagram):
 * start a sale, add / change / remove items, read the totals, cancel, and
 * finalize it once paid ({@link #finalizeSale}).
 *
 * Rules applied on every change:
 * - one line per product; adding a product that is already in the sale adds
 *   to its line;
 * - the line is re-priced on every quantity change (retail below 6,
 *   wholesale from 6 - see {@link ThresholdPricingRule});
 * - products that are no longer sold are refused;
 * - adding or raising a line beyond the product's stock is refused with
 *   {@link InsufficientStockException}, and the sale is left as it was;
 * - lowering a line is always allowed. If stock dropped after the item was
 *   added, the line can still be above the stock: {@link #findStockProblems()}
 *   reports it, {@link #canCheckout()} is false, and the screen greys out Pay
 *   until the cashier lowers or removes the line.
 *
 * Products are found ONLY through {@link ProductLookup} (the Product &amp;
 * Inventory module's contract), so this runs on the test stand-in now and on
 * the real inventory later with no change. Database problems from the lookup
 * come out as RuntimeException; show them with shared.UiErrors.
 *
 * One SalesService per terminal; it is not meant to be used from several
 * threads at once (the JavaFX screen calls it from the UI thread).
 */
public class SalesService {

    private final ProductLookup products;
    private final PricingService pricing;
    private final String terminalId;
    private final CheckoutService checkout;
    private Sale current;

    /**
     * The full service used by the app.
     *
     * @param terminalId this terminal's code, e.g. "T01" (goes into invoice numbers)
     * @param checkout   saves paid sales (see CheckoutService)
     */
    public SalesService(ProductLookup products, PricingService pricing, String terminalId,
                        CheckoutService checkout) {
        this.products = Objects.requireNonNull(products, "products");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        InvoiceNumberGenerator.checkTerminalId(terminalId);
        this.terminalId = terminalId;
        this.checkout = checkout;
    }

    /** Cart only, no checkout (tests and tools); finalizeSale is not available. */
    public SalesService(ProductLookup products, PricingService pricing, String terminalId) {
        this(products, pricing, terminalId, null);
    }

    /** Cart only, with the store's pricing rule. */
    public SalesService(ProductLookup products, String terminalId) {
        this(products, new PricingService(), terminalId, null);
    }

    /* ------------------------------ the sale ------------------------------ */

    /**
     * Starts a new, empty sale for the logged-in cashier.
     *
     * @throws IllegalStateException a sale is already open (finish or cancel it first)
     */
    public Sale startSale(String cashierId) {
        if (current != null && current.getStatus() == SaleStatus.OPEN) {
            throw new IllegalStateException("A sale is already open; finish or cancel it first");
        }
        current = new Sale(terminalId, cashierId);
        return current;
    }

    /** The open sale, if there is one. */
    public Optional<Sale> currentSale() {
        return Optional.ofNullable(current).filter(s -> s.getStatus() == SaleStatus.OPEN);
    }

    /** Cancels the open sale. Nothing is saved and no stock is touched. */
    public void cancelSale() {
        open().cancel();
        current = null;
    }

    /* ------------------------------ adding -------------------------------- */

    /** Adds 1 of the product with this barcode (one scan). */
    public SaleLineItem addItem(String barcode) throws CartException, InsufficientStockException {
        return addItem(barcode, Quantity.ONE);
    }

    /**
     * Adds {@code quantity} of the product with this barcode (e.g. the cashier
     * types 12, then scans). Adds to the product's line if it is already there.
     */
    public SaleLineItem addItem(String barcode, BigDecimal quantity)
            throws CartException, InsufficientStockException {
        Sale sale = open();
        String code = barcode == null ? "" : barcode.trim();
        if (code.isEmpty()) {
            throw new CartException("Scan or type a barcode.");
        }
        ProductInfo product = products.findByBarcode(code).orElseThrow(() -> new CartException(
                "No product has the barcode " + code + ". Search for it by name, "
                        + "or ask inventory staff to register it."));
        return add(sale, product, quantity);
    }

    /** Adds 1 of a product picked from search (products without a barcode). */
    public SaleLineItem addProduct(String productId) throws CartException, InsufficientStockException {
        return addProduct(productId, Quantity.ONE);
    }

    /** Adds {@code quantity} of a product picked from search. */
    public SaleLineItem addProduct(String productId, BigDecimal quantity)
            throws CartException, InsufficientStockException {
        Sale sale = open();
        return add(sale, find(productId), quantity);
    }

    /* ------------------------------ changing ------------------------------ */

    /**
     * Sets a line's quantity (decimals allowed, e.g. 1.5 kg). Raising it checks
     * that the product is still sold and in stock; the line is re-priced either way.
     */
    public SaleLineItem updateQuantity(String productId, BigDecimal quantity)
            throws CartException, InsufficientStockException {
        Sale sale = open();
        SaleLineItem line = sale.findLine(productId)
                .orElseThrow(() -> new CartException("That product is not in this sale."));
        BigDecimal qty = checkQuantity(quantity);
        ProductInfo product = find(productId);
        if (qty.compareTo(line.quantity()) > 0) {
            checkCanSell(product, qty);
        }
        SaleLineItem priced = pricing.priceLine(product, qty);
        sale.addLine(priced);
        return priced;
    }

    /** Removes a product's line. */
    public void removeItem(String productId) throws CartException {
        if (!open().removeLine(productId)) {
            throw new CartException("That product is not in this sale.");
        }
    }

    /** Removes every line; the sale stays open. */
    public void clearItems() {
        open().clear();
    }

    /* ------------------------------ totals -------------------------------- */

    /** What the customer pays for the open sale. */
    public BigDecimal getTotal() {
        return open().total();
    }

    /** Total with the VAT breakdown for the open sale. */
    public SaleTotals getTotals() {
        return open().totals();
    }

    /* ------------------------------ checkout ------------------------------ */

    /**
     * Completes the open sale with {@code payment} (from PaymentService) and
     * returns the saved sale with its invoice number. On success the sale is
     * COMPLETED and there is no open sale until startSale is called again.
     *
     * On ANY failure nothing is saved, no stock changes, no invoice number is
     * used, and the sale stays OPEN exactly as it was, so the cashier can fix
     * the problem (e.g. lower a quantity) and press Pay again.
     *
     * @throws CartException              the sale is empty, or a line has a stock problem
     *                                    (message from {@link StockProblem#message()})
     * @throws CheckoutException          the payment no longer matches the total (take it again)
     * @throws InsufficientStockException stock ran short at the moment of saving
     * @throws RuntimeException           database problem; show with shared.UiErrors
     */
    public SaleRecord finalizeSale(PaymentRecord payment)
            throws CartException, CheckoutException, InsufficientStockException {
        Sale sale = open();
        if (checkout == null) {
            throw new IllegalStateException("This SalesService was made without checkout");
        }
        Objects.requireNonNull(payment, "payment");
        if (sale.isEmpty()) {
            throw new CartException("Add an item before taking payment.");
        }
        List<StockProblem> problems = findStockProblems();
        if (!problems.isEmpty()) {
            throw new CartException(problems.get(0).message());
        }
        if (payment.amountApplied().compareTo(sale.total()) != 0) {
            throw new CheckoutException("The total changed after the payment was entered. "
                    + "Enter the payment again.");
        }
        SaleRecord saved = checkout.complete(sale, payment);
        sale.complete();
        current = null;
        return saved;
    }

    /* --------------------------- stock problems --------------------------- */

    /**
     * Re-checks every line against the CURRENT stock and returns the lines that
     * can't be sold as they stand (empty list = all fine). Stock can drop
     * after an item was added, so the screen calls this after every change and
     * greys out Pay while the list is not empty.
     *
     * Checkout (Phase 2) still checks stock once more, inside the transaction
     * that saves the sale, so a sale can never go through short even if
     * stock drops between this check and Pay.
     */
    public List<StockProblem> findStockProblems() {
        List<StockProblem> problems = new ArrayList<>();
        for (SaleLineItem line : open().getItems()) {
            Optional<ProductInfo> now = products.findById(line.productId());
            if (now.isEmpty() || !now.get().active()) {
                problems.add(new StockProblem(line.productId(), line.productName(), line.quantity(),
                        now.map(ProductInfo::stockOnHand).orElse(BigDecimal.ZERO), true));
            } else if (line.quantity().compareTo(now.get().stockOnHand()) > 0) {
                problems.add(new StockProblem(line.productId(), line.productName(), line.quantity(),
                        now.get().stockOnHand(), false));
            }
        }
        return problems;
    }

    /** True when Pay can be enabled: a sale is open, has lines, and has no stock problems. */
    public boolean canCheckout() {
        return currentSale().map(s -> !s.isEmpty()).orElse(false) && findStockProblems().isEmpty();
    }

    /* ------------------------------ helpers ------------------------------- */

    private SaleLineItem add(Sale sale, ProductInfo product, BigDecimal quantity)
            throws CartException, InsufficientStockException {
        BigDecimal qty = checkQuantity(quantity);
        BigDecimal newQty = sale.findLine(product.productId())
                .map(l -> l.quantity().add(qty))
                .orElse(qty);
        checkCanSell(product, newQty);
        SaleLineItem priced = pricing.priceLine(product, newQty);
        sale.addLine(priced);
        return priced;
    }

    private void checkCanSell(ProductInfo product, BigDecimal lineQuantity)
            throws CartException, InsufficientStockException {
        if (!product.active()) {
            throw new CartException(product.name() + " is no longer sold.");
        }
        if (lineQuantity.compareTo(product.stockOnHand()) > 0) {
            throw new InsufficientStockException(product.productId(), product.name(),
                    product.stockOnHand(), lineQuantity);
        }
    }

    private ProductInfo find(String productId) throws CartException {
        return products.findById(productId == null ? "" : productId)
                .orElseThrow(() -> new CartException("That product could not be found."));
    }

    private static BigDecimal checkQuantity(BigDecimal quantity) throws CartException {
        if (quantity == null || Quantity.normalize(quantity).signum() <= 0) {
            throw new CartException("Quantity must be more than 0.");
        }
        return Quantity.normalize(quantity);
    }

    private Sale open() {
        if (current == null || current.getStatus() != SaleStatus.OPEN) {
            throw new IllegalStateException("No sale is open; call startSale first");
        }
        return current;
    }
}
