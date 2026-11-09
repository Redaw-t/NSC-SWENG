package pos.receipt;

import pos.contract.ReturnRecord;
import pos.contract.SaleRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Makes the {@link Receipt} for a saved sale (ReceiptBuilder in the class
 * diagram), and the RETURN slip for a void. Formatting only: it does not print or save anything.
 */
public class ReceiptBuilder {

    private final Supplier<StoreInfo> store;   // asked on every receipt, so a change shows straight away
    private final Function<String, String> cashierNames;
    private final Function<String, List<ReturnRecord>> returnsOfSale;

    /**
     * @param cashierNames user ID -> the person's name (e.g. "Juan Dela Cruz");
     *                     may return null, then the user ID is printed
     */
    public ReceiptBuilder(StoreInfo store, Function<String, String> cashierNames) {
        this(store, cashierNames, saleId -> List.of());
    }

    /**
     * @param returnsOfSale sale ID -> its voids, oldest first (shown on reprinted invoices)
     */
    public ReceiptBuilder(StoreInfo store, Function<String, String> cashierNames,
                          Function<String, List<ReturnRecord>> returnsOfSale) {
        this(fixed(store), cashierNames, returnsOfSale);
    }

    /**
     * @param store the store details in use, asked for each receipt (e.g. the newest row of the
     *              StoreDetails log, see system.service.StoreDetailsService)
     */
    public ReceiptBuilder(Supplier<StoreInfo> store, Function<String, String> cashierNames,
                          Function<String, List<ReturnRecord>> returnsOfSale) {
        this.store = Objects.requireNonNull(store, "store");
        this.cashierNames = Objects.requireNonNull(cashierNames, "cashierNames");
        this.returnsOfSale = Objects.requireNonNull(returnsOfSale, "returnsOfSale");
    }

    /** The receipt handed over right after the sale. */
    public Receipt build(SaleRecord sale) {
        return new Receipt(store(), sale, nameOf(sale.cashierUserId()), false);
    }

    /**
     * A copy printed later, marked REPRINT, with every void of the sale listed below its items.
     *
     * @throws RuntimeException the voids could not be read (a copy without them would be wrong)
     */
    public Receipt buildReprint(SaleRecord sale) {
        return new Receipt(store(), sale, nameOf(sale.cashierUserId()), true, refunds(returnsOfSale.apply(sale.saleId())),
                null);
    }

    /** The RETURN slip for one void of {@code sale}. */
    public Receipt buildReturn(SaleRecord sale, ReturnRecord ret, boolean reprint) {
        return new Receipt(store(), sale, nameOf(sale.cashierUserId()), reprint, List.of(),
                new Receipt.Refund(ret, nameOf(ret.cashierUserId())));
    }

    private List<Receipt.Refund> refunds(List<ReturnRecord> returns) {
        List<Receipt.Refund> out = new ArrayList<>();
        for (ReturnRecord r : returns) {
            out.add(new Receipt.Refund(r, nameOf(r.cashierUserId())));
        }
        return out;
    }

    private String nameOf(String userId) {
        try {
            return cashierNames.apply(userId);
        } catch (RuntimeException e) {
            return null;   // e.g. database down: print the user ID instead
        }
    }

    private StoreInfo store() {
        return Objects.requireNonNull(store.get(), "store");
    }

    private static Supplier<StoreInfo> fixed(StoreInfo store) {
        Objects.requireNonNull(store, "store");
        return () -> store;
    }
}
