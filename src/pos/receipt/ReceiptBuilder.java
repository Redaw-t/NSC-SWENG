package pos.receipt;

import pos.contract.SaleRecord;

import java.util.Objects;
import java.util.function.Function;

/**
 * Makes the {@link Receipt} for a saved sale (ReceiptBuilder in the class
 * diagram). Formatting only: it does not print or save anything.
 */
public class ReceiptBuilder {

    private final StoreInfo store;
    private final Function<String, String> cashierNames;

    /**
     * @param cashierNames user ID -> the person's name (e.g. "Juan Dela Cruz");
     *                     may return null, then the user ID is printed
     */
    public ReceiptBuilder(StoreInfo store, Function<String, String> cashierNames) {
        this.store = Objects.requireNonNull(store, "store");
        this.cashierNames = Objects.requireNonNull(cashierNames, "cashierNames");
    }

    /** The receipt handed over right after the sale. */
    public Receipt build(SaleRecord sale) {
        return new Receipt(store, sale, nameOf(sale), false);
    }

    /** A copy printed later, marked REPRINT. */
    public Receipt buildReprint(SaleRecord sale) {
        return new Receipt(store, sale, nameOf(sale), true);
    }

    private String nameOf(SaleRecord sale) {
        try {
            return cashierNames.apply(sale.cashierUserId());
        } catch (RuntimeException e) {
            return null;   // e.g. database down: print the user ID instead
        }
    }
}
