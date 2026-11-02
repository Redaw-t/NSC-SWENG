package inventory.service;

/**
 * A problem the person using Inventory can act on (bad quantity, not enough
 * stock, no permission...). The message is ready to show as it is.
 */
public class InventoryException extends Exception {

    private static final long serialVersionUID = 1L;

    public InventoryException(String message) {
        super(message);
    }
}
