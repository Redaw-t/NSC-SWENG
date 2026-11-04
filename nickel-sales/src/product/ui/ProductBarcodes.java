package product.ui;

import product.contract.BarcodeAssignException;
import product.contract.BarcodeAssigner;
import product.contract.BarcodeEntry;
import product.service.ProductException;
import product.service.ProductService;

import java.util.List;

/**
 * What the Product module gives the Barcode tab: lists products and saves a barcode through
 * {@link ProductService}, as the user logged in RIGHT NOW (read again on every call, so a role
 * taken away is noticed at once). Product messages are passed on as they are.
 */
final class ProductBarcodes implements BarcodeAssigner {

    private final ProductService products;
    private final ActorSource actor;

    ProductBarcodes(ProductService products, ActorSource actor) {
        this.products = products;
        this.actor = actor;
    }

    @Override
    public List<BarcodeEntry> listEntries() throws BarcodeAssignException {
        try {
            return products.listBarcodeEntries(actor.current());
        } catch (ProductException e) {
            throw new BarcodeAssignException(e.getMessage());
        }
    }

    @Override
    public void assignBarcode(String productId, String barcode) throws BarcodeAssignException {
        try {
            products.assignBarcode(actor.current(), productId, barcode);
        } catch (ProductException e) {
            throw new BarcodeAssignException(e.getMessage());
        }
    }
}
