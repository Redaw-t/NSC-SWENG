package product.ui;

import app.AppModule;
import app.ComingSoonView;
import app.ScreenContext;
import auth.permission.Permission;
import javafx.scene.Parent;
import product.service.CategoryService;
import product.service.ProductService;
import product.service.SupplierService;

/**
 * "Products" on the home screen: opens {@link ProductScreen} (Admin and Inventory
 * staff, who hold MANAGE_PRODUCTS). Needs ProductService, CategoryService and
 * SupplierService to be registered in app.Main.
 */
public final class ProductModule implements AppModule {

    @Override
    public String title() {
        return "Products";
    }

    @Override
    public String description() {
        return "Products, prices, categories, suppliers";
    }

    @Override
    public Permission permission() {
        return Permission.MANAGE_PRODUCTS;
    }

    @Override
    public boolean usesShell() {
        return true;
    }

    @Override
    public Parent open(ScreenContext screen) {
        ProductService products = screen.app().find(ProductService.class).orElse(null);
        CategoryService categories = screen.app().find(CategoryService.class).orElse(null);
        SupplierService suppliers = screen.app().find(SupplierService.class).orElse(null);
        if (products == null || categories == null || suppliers == null) {
            return notConnected();
        }
        return new ProductScreen(screen, products, categories, suppliers).getView();
    }

    private static Parent notConnected() {
        return ComingSoonView.notice("Products", "The product catalog is not connected in this build.");
    }
}
