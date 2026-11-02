package product.ui;

import app.ComingSoonView;
import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import product.service.CategoryService;
import product.service.ProductService;
import product.service.SupplierService;
import shared.Styles;

import java.util.EnumMap;
import java.util.Map;

/**
 * Product management, laid out like the design: a tab strip (Products | Categories |
 * Suppliers | Barcode) above the tab's own screen. It is shown inside the app's sidebar
 * frame (see app.AppShell), so it has no Back button; the sidebar's Dashboard goes home.
 *
 * The tabs are {@link ProductsPane}, {@link CategoriesPane} and {@link SuppliersPane};
 * Barcode is not built yet and shows a "Coming soon" card.
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
    private final Parent barcodePlaceholder = ComingSoonView.create("Barcode",
            "Barcode generation and label printing aren't built yet.");

    private final Map<Tab, Button> tabButtons = new EnumMap<>(Tab.class);
    private final BorderPane body = new BorderPane();
    private final VBox view = new VBox(20);

    public ProductScreen(ScreenContext screen, ProductService products, CategoryService categories,
                         SupplierService suppliers) {
        // Read the user fresh on every click, so a role taken away is noticed at once.
        ActorSource actor = () -> screen.app().auth().getCurrentUser(screen.sessionId());
        this.productsPane = new ProductsPane(screen, products, categories, suppliers, actor);
        this.categoriesPane = new CategoriesPane(categories, actor);
        this.suppliersPane = new SuppliersPane(suppliers, actor);

        HBox bar = new HBox();
        Styles.tabBar(bar);
        bar.setAlignment(Pos.CENTER);
        boolean first = true;
        for (Tab tab : Tab.values()) {
            if (!first) {
                Label divider = new Label("|");
                Styles.tabDivider(divider);
                bar.getChildren().add(divider);
            }
            first = false;
            Button button = new Button(tab.label);
            Styles.tab(button, false);
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setOnAction(e -> show(tab));
            tabButtons.put(tab, button);
            bar.getChildren().add(button);
        }

        view.getChildren().addAll(bar, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        show(Tab.PRODUCTS);   // if loading fails, the message shows and the other tabs and the sidebar still work
    }

    public Parent getView() {
        return view;
    }

    private void show(Tab tab) {
        tabButtons.forEach((t, button) -> {
            Styles.tab(button, t == tab);
        });
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
            case BARCODE -> body.setCenter(barcodePlaceholder);
        }
    }
}
