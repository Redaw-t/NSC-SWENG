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
    private SaleStatus status = SaleStatus.OPEN;

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
        return items.removeIf(l -> l.productId().equals(productId));
    }

    void clear() {
        requireOpen();
        items.clear();
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
