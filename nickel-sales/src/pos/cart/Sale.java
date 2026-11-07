package pos.cart;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The sale being rung up on this terminal (Sale in the class diagram).
 *
 * Screens and other classes can READ it; only {@link SalesService} changes
 * it (the changing methods are package-private), so every line on it has
 * been priced and stock-checked. Lines stay in the order they were added.
 */
public final class Sale {

    private final String saleId = UUID.randomUUID().toString();
    private final String terminalId;
    private final String cashierId;
    private final Instant createdAt = Instant.now();
    private final List<SaleLineItem> items = new ArrayList<>();
    /** Lines whose unit price the cashier changed (F4), by product ID, in the order edited. */
    private final java.util.Map<String, PriceEdit> priceEdits = new java.util.LinkedHashMap<>();
    private SaleStatus status = SaleStatus.OPEN;

    /**
     * A unit price changed by the cashier for this sale only (F4 Edit Price). The product's own
     * price in the catalog is never changed.
     *
     * @param originalPrice the line's unit price before the first edit (VAT included)
     * @param newPrice      the price the cashier typed (VAT included, 0 or more)
     */
    public record PriceEdit(String productId, String productName, BigDecimal originalPrice, BigDecimal newPrice) { }

    Sale(String terminalId, String cashierId) {
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.cashierId = Objects.requireNonNull(cashierId, "cashierId");
    }

    /** UUID; becomes the saved sale's ID at checkout. */
    public String getSaleId() {
        return saleId;
    }

    public String getTerminalId() {
        return terminalId;
    }

    /** The logged-in cashier's user ID, e.g. "NSC-00002". */
    public String getCashierId() {
        return cashierId;
    }

    /** When the sale was started (UTC). */
    public Instant getCreatedAt() {
        return createdAt;
    }

    public SaleStatus getStatus() {
        return status;
    }

    /** The lines, in the order they were added (a read-only copy). */
    public List<SaleLineItem> getItems() {
        return List.copyOf(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The line for this product, if it is in the sale. */
    public Optional<SaleLineItem> findLine(String productId) {
        return items.stream().filter(l -> l.productId().equals(productId)).findFirst();
    }

    /** True if the cashier changed this line's unit price (shown as PRICE EDITED). */
    public boolean isPriceEdited(String productId) {
        return priceEdits.containsKey(productId);
    }

    /** The edited price of this line, if it was edited. */
    public Optional<PriceEdit> priceEdit(String productId) {
        return Optional.ofNullable(priceEdits.get(productId));
    }

    /** Every price edit still in the sale (lines removed since are left out), in the order edited. */
    public List<PriceEdit> getPriceEdits() {
        return List.copyOf(priceEdits.values());
    }

    /** Total, VATable, VAT-exempt and VAT amounts. */
    public SaleTotals totals() {
        return SaleTotals.of(items);
    }

    /** What the customer pays. */
    public BigDecimal total() {
        return totals().total();
    }

    /* ---------------- changes: SalesService only ---------------- */

    /** Adds a new line, or replaces the product's line in the same position. */
    void addLine(SaleLineItem line) {
        requireOpen();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).productId().equals(line.productId())) {
                items.set(i, line);
                return;
            }
        }
        items.add(line);
    }

    boolean removeLine(String productId) {
        requireOpen();
        priceEdits.remove(productId);
        return items.removeIf(l -> l.productId().equals(productId));
    }

    void clear() {
        requireOpen();
        items.clear();
        priceEdits.clear();
    }

    /** Remembers an edited price; the original is kept from the first edit. */
    void putPriceEdit(PriceEdit edit) {
        requireOpen();
        PriceEdit first = priceEdits.get(edit.productId());
        priceEdits.put(edit.productId(), first == null ? edit
                : new PriceEdit(edit.productId(), edit.productName(), first.originalPrice(), edit.newPrice()));
    }

    void cancel() {
        requireOpen();
        status = SaleStatus.CANCELLED;
    }

    void complete() {
        requireOpen();
        status = SaleStatus.COMPLETED;
    }

    private void requireOpen() {
        if (status != SaleStatus.OPEN) {
            throw new IllegalStateException("The sale is " + status + " and can no longer change");
        }
    }
}
