package product.ui;

import app.ComingSoonView;
import app.ScreenContext;
import barcode.service.BarcodeService;
import barcode.service.LabelPrinterPort;
import barcode.ui.BarcodePane;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import product.service.CategoryService;
import product.service.ProductService;
import product.service.SupplierService;
import shared.TabBar;

import java.util.Arrays;

/**
 * Product management, laid out like the design: a tab strip (Products | Categories |
 * Suppliers | Barcode) above the tab's own screen. It is shown inside the app's sidebar
 * frame (see app.AppShell), so it has no Back button; the sidebar's Dashboard goes home.
 *
 * The tabs are {@link ProductsPane}, {@link CategoriesPane}, {@link SuppliersPane} and the
 * Barcode module's {@link BarcodePane}. Barcode lists and saves barcodes only through the
 * {@code product.contract.BarcodeAssigner} contract ({@link ProductBarcodes}). If no label printer
 * is connected in this build, the Barcode tab shows a notice instead.
 */
public class ProductScreen {

    private enum Tab {
        PRODUCTS("Products"), CATEGORIES("Categories"), SUPPLIERS("Suppliers"), BARCODE("Barcode");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private final ProductsPane productsPane;
    private final CategoriesPane categoriesPane;
    private final SuppliersPane suppliersPane;
    private final BarcodePane barcodePane;          // null when the Barcode module is not connected
    private final Parent barcodeUnavailable = ComingSoonView.notice("Barcode",
            "Barcode management is not connected in this build.");

    private final TabBar tabs = new TabBar(Arrays.stream(Tab.values()).map(t -> t.label).toList(),
            number -> show(Tab.values()[number]));
    private final BorderPane body = new BorderPane();
    private final VBox view = new VBox(20);

    public ProductScreen(ScreenContext screen, ProductService products, CategoryService categories,
                         SupplierService suppliers, LabelPrinterPort labelPrinter) {
        // Read the user fresh on every click, so a role taken away is noticed at once.
        ActorSource actor = () -> screen.app().auth().getCurrentUser(screen.sessionId());
        this.productsPane = new ProductsPane(screen, products, categories, suppliers, actor);
        this.categoriesPane = new CategoriesPane(categories, actor);
        this.suppliersPane = new SuppliersPane(suppliers, actor);
        this.barcodePane = labelPrinter == null ? null
                : new BarcodePane(new BarcodeService(products, new ProductBarcodes(products, actor), labelPrinter));

        // The tabs, and under them on the right the Refresh button: loads the shown tab again.
        view.getChildren().addAll(shared.PageHeader.of(tabs.getView(), () -> show(shown)), body);
        VBox.setVgrow(body, Priority.ALWAYS);
        show(Tab.PRODUCTS);   // if loading fails, the message shows and the other tabs and the sidebar still work
    }

    public Parent getView() {
        return view;
    }

    private Tab shown = Tab.PRODUCTS;

    private void show(Tab tab) {
        shown = tab;
        tabs.select(tab.ordinal());
        switch (tab) {
            case PRODUCTS -> {
                body.setCenter(productsPane.getView());
                productsPane.refresh();
            }
            case CATEGORIES -> {
                body.setCenter(categoriesPane.getView());
                categoriesPane.refresh();
            }
            case SUPPLIERS -> {
                body.setCenter(suppliersPane.getView());
                suppliersPane.refresh();
            }
            case BARCODE -> {
                if (barcodePane == null) {
                    body.setCenter(barcodeUnavailable);
                } else {
                    body.setCenter(barcodePane.getView());
                    barcodePane.refresh();
                }
            }
        }
    }
}
