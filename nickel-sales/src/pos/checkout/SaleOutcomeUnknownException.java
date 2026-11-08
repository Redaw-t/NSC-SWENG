package pos.checkout;

/**
 * Saving a sale failed in a way that leaves it unknown whether it was saved: the database
 * reported an error (e.g. the connection dropped while committing) and could not be asked
 * afterwards. The sale stays OPEN. Paying again is safe: SalesService.finalizeSale first
 * looks for the sale, and if the earlier attempt did save it, returns that instead of
 * saving it twice. The cashier must NOT cancel it, or a saved sale would look cancelled.
 */
public class SaleOutcomeUnknownException extends RuntimeException {

    public SaleOutcomeUnknownException(String saleId, Throwable cause) {
        super("Could not tell whether sale " + saleId + " was saved", cause);
    }
}
