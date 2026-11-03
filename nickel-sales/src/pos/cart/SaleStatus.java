package pos.cart;

/**
 * Where a sale is in its life (SaleStatus in the class diagram).
 * Only COMPLETED sales are saved (as a SaleRecord); OPEN and CANCELLED ones
 * exist only on the terminal while the cashier works.
 */
public enum SaleStatus {
    OPEN,
    COMPLETED,
    CANCELLED
}
