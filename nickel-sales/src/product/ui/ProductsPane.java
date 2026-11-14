package product.ui;

import app.ScreenContext;
import auth.contract.User;
import auth.permission.Permission;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import product.model.Category;
import product.model.Product;
import product.model.Supplier;
import product.service.CategoryService;
import product.service.ProductException;
import product.service.ProductService;
import product.service.SupplierService;
import shared.Modal;
import shared.Money;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;
import shared.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Products" tab: search, Category / Status / Supplier filters, "+ Add Product" and the
 * product table with View, Edit and Deactivate buttons on every row, 20 rows to a page.
 * Prices includes VAT, as in POS. Stock is shown only in the View dialog and is never
 * edited here (Inventory owns it). Every action goes through the services, which check the
 * permission again and write the audit log.
 */
final class ProductsPane {

    /** The "no filter" choices at the top of the drop-downs. */
    private static final Category ALL_CATEGORIES = new Category(null, "All Categories", true);
    private static final Supplier ALL_SUPPLIERS = new Supplier(null, "All Suppliers", null, null, null, null, true);
    private static final Category NO_CATEGORY = new Category(-1, "(No category)", true);
    private static final Supplier NO_SUPPLIER = new Supplier(-1, "(No supplier)", null, null, null, null, true);

    private enum StatusFilter {
        ALL("All Statuses"), ACTIVE("Active"), INACTIVE("Inactive");

        private final String label;

        StatusFilter(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private interface Action {
        void run() throws ProductException;
    }

    private final ScreenContext screen;
    private final ProductService products;
    private final CategoryService categories;
    private final SupplierService suppliers;
    private final ActorSource actor;

    private final TextField search = new TextField();
    private final SearchPicker<Category> categoryFilter = new SearchPicker<>(false);
    private final SearchPicker<StatusFilter> statusFilter = new SearchPicker<>(false, false);   // same look as the others, no search box
    private final SearchPicker<Supplier> supplierFilter = new SearchPicker<>(false);
    private final TableView<Product> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label message = new Label();
    private final VBox view = new VBox(16);

    private List<Product> loaded = List.of();      // what the database returned for the search + status
    private List<Product> filtered = List.of();    // loaded, narrowed by category and supplier
    private boolean choicesStale = true;           // reload the category / supplier drop-downs next time
    private boolean updatingChoices;               // true while we fill the drop-downs ourselves

    ProductsPane(ScreenContext screen, ProductService products, CategoryService categories,
                 SupplierService suppliers, ActorSource actor) {
        this.screen = screen;
        this.products = products;
        this.categories = categories;
        this.suppliers = suppliers;
        this.actor = actor;

        table.getColumns().add(Tables.<Product>numberColumn(pager));
        table.getColumns().add(Tables.<Product>nodeColumn("Product Code", 130, 110, this::codeCell));
        table.getColumns().add(Tables.<Product>textColumn("Product Name", 280, Product::name));
        table.getColumns().add(Tables.<Product>textColumn("Category", 110,
                p -> p.categoryName() == null ? "" : p.categoryName()));
        table.getColumns().add(Tables.<Product>textColumn("Supplier", 120,
                p -> p.supplierName() == null ? "" : p.supplierName()));
        table.getColumns().add(Tables.<Product>textColumn("Retail\nPrice", 80,
                p -> Money.format(p.retailPrice())));
        table.getColumns().add(Tables.<Product>textColumn("Wholesale\nPrice", 90,
                p -> p.wholesalePrice() == null ? "" : Money.format(p.wholesalePrice())));
        table.getColumns().add(Tables.<Product>nodeColumn("VAT", 60, 56, this::vatCell));
        table.getColumns().add(Tables.<Product>nodeColumn("Status", 90, 90, p -> ProductUi.pill(p.active())));
        table.getColumns().add(Tables.<Product>nodeColumn("Actions", 220, 215, this::actions));
        table.setPlaceholder(new Label("No products found."));
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        search.setPromptText("Search Product...");
        search.setPrefWidth(300);
        Styles.input(search);
        search.textProperty().addListener((obs, old, text) -> reload(false));

        categoryFilter.setValue(ALL_CATEGORIES);   // shows "All Categories" until one is picked
        categoryFilter.setPrefWidth(180);
        categoryFilter.valueProperty().addListener((obs, old, c) -> {
            if (!updatingChoices) {
                applyFilters();
            }
        });

        statusFilter.setPrefWidth(160);
        statusFilter.getItems().setAll(StatusFilter.values());
        statusFilter.setValue(StatusFilter.ALL);
        statusFilter.valueProperty().addListener((obs, old, s) -> reload(false));   // changes what is fetched

        supplierFilter.setValue(ALL_SUPPLIERS);
        supplierFilter.setPrefWidth(180);
        supplierFilter.valueProperty().addListener((obs, old, s) -> {
            if (!updatingChoices) {
                applyFilters();
            }
        });

        Button add = ProductUi.primaryButton("+ Add Product");
        add.setOnAction(e -> addProduct());

        Button reset = Styles.resetButton(() -> {
            updatingChoices = true;
            categoryFilter.setValue(ALL_CATEGORIES);
            supplierFilter.setValue(ALL_SUPPLIERS);
            updatingChoices = false;
            search.clear();
            statusFilter.setValue(StatusFilter.ALL);
            reload(false);
        });
        HBox toolbar = new HBox(12, search, categoryFilter, statusFilter, supplierFilter, reset, add);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        message.setWrapText(true);
        Styles.error(message);

        view.getChildren().addAll(toolbar, table, message, pager.getView());
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    Parent getView() {
        return view;
    }

    /** Called every time the tab is shown: reloads the list and the filter choices. */
    void refresh() {
        choicesStale = true;
        reload(view.getScene() != null);
    }

    /* ------------------------------- table rows ------------------------------ */

    private Node actions(Product p) {
        Button viewButton = ProductUi.rowButton("View", ProductUi.RowColour.VIEW);
        viewButton.setOnAction(e -> ProductDetailsDialog.show(owner(), p));
        Button edit = ProductUi.rowButton("Edit", ProductUi.RowColour.EDIT);
        edit.setOnAction(e -> editProduct(p));
        Button toggle = ProductUi.toggleButton(p.active());
        toggle.setOnAction(e -> toggle(p));
        HBox box = new HBox(8, viewButton, edit, toggle);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** The Code cell: the barcode, or a faint "No Barcode" when the product has none. */
    private Node codeCell(Product p) {
        if (p.barcode() == null || p.barcode().isBlank()) {
            Label none = new Label("No Barcode");
            Styles.muted(none);
            none.setOpacity(0.5);      // lower this for fainter text
            return none;
        }
        Label code = new Label(p.barcode());
        code.setStyle("-fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return code;
    }

    /** The VAT cell: a green check when VAT applies to the product, empty when it is VAT-exempt. */
    private Node vatCell(Product p) {
        Label mark = new Label(p.vatable() ? "\u2714" : "");
        mark.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.SUCCESS + ";");
        mark.setAccessibleText(p.vatable() ? "VAT applies" : "VAT-exempt");
        return mark;
    }

    /* -------------------------------- actions -------------------------------- */

    private void addProduct() {
        openForm(null);
    }

    private void editProduct(Product p) {
        openForm(p);
    }

    /** Opens the add (existing == null) or edit form with the current categories and suppliers. */
    private void openForm(Product existing) {
        List<Category> cats;
        List<Supplier> sups;
        try {
            cats = categories.listCategories(actor.current(), true);
            sups = suppliers.listSuppliers(actor.current(), true);
        } catch (ProductException e) {
            reload(true);
            message.setText(e.getMessage());
            return;
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e));
            return;
        }

        String[] saved = {null};
        if (existing == null) {
            ProductFormDialog.show(owner(), null, cats, sups, draft -> {
                Product p = products.createProduct(actor.current(), draft);
                saved[0] = p.name() + " was added.";
            });
        } else {
            ProductFormDialog.show(owner(), existing, cats, sups, draft -> {
                Product p = products.updateProduct(actor.current(), existing.productId(), draft);
                saved[0] = p.name() + " was updated.";
            });
        }
        reload(true);
        if (saved[0] != null) {
            Toast.success(owner(), "Product saved", saved[0]);
        }
    }

    private void toggle(Product p) {
        if (p.active() && !Modal.confirm(owner(), "Deactivate product",
                "Deactivate \"" + p.name() + "\"?\n\nPOS will stop selling it. You can reactivate it at any time.",
                "Deactivate")) {
            return;
        }
        attempt(() -> {
            products.setActive(actor.current(), p.productId(), !p.active());
            Toast.success(owner(), p.active() ? "Product deactivated" : "Product reactivated", p.name());
        });
    }

    /** Runs the action, then reloads. A failed action shows its message under the table. */
    private void attempt(Action action) {
        try {
            action.run();
        } catch (ProductException e) {
            reload(true);                       // leaves the screen if access was lost
            message.setText(e.getMessage());    // otherwise say why it failed
            return;
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e));
            return;
        }
        reload(true);
    }

    /* ------------------------------ loading & paging ------------------------------ */

    /**
     * Reloads the list from the database. If the user may no longer manage products
     * (role removed, account deactivated, session ended) and {@code leaveIfDenied}
     * is set, we explain and go back to the home screen.
     */
    private void reload(boolean leaveIfDenied) {
        if (leaveIfDenied && view.getScene() == null) {
            return;   // this screen is no longer displayed: nothing to refresh
        }
        User current = actor.current();
        if (leaveIfDenied && (current == null
                || !screen.app().authz().checkAccess(current, Permission.MANAGE_PRODUCTS))) {
            Modal.notice(owner(), "Access changed",
                    "You can no longer manage products.\n\nYou will be returned to the home screen.");
            screen.goHome();
            return;
        }
        try {
            if (choicesStale) {
                loadChoices(current);
                choicesStale = false;
            }
            boolean includeInactive = statusFilter.getValue() != StatusFilter.ACTIVE;
            loaded = products.listProducts(current, search.getText(), includeInactive);
            applyFilters();
            message.setText("");
        } catch (ProductException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e));
        }
    }

    /** Fills the Category and Supplier drop-downs, keeping what is selected. */
    private void loadChoices(User current) throws ProductException {
        List<Category> cats = new ArrayList<>();
        cats.add(ALL_CATEGORIES);
        cats.addAll(categories.listCategories(current, true));
        cats.add(NO_CATEGORY);
        List<Supplier> sups = new ArrayList<>();
        sups.add(ALL_SUPPLIERS);
        sups.addAll(suppliers.listSuppliers(current, true));
        sups.add(NO_SUPPLIER); 

        Category keepCategory = categoryFilter.getValue();
        Supplier keepSupplier = supplierFilter.getValue();
        updatingChoices = true;
        try {
            categoryFilter.getItems().setAll(cats);
            categoryFilter.setValue(keepCategory == null ? null : cats.stream()
                    .filter(c -> sameId(c.categoryId(), keepCategory.categoryId())).findFirst().orElse(null));
            supplierFilter.getItems().setAll(sups);
            supplierFilter.setValue(keepSupplier == null ? null : sups.stream()
                    .filter(s -> sameId(s.supplierId(), keepSupplier.supplierId())).findFirst().orElse(null));
        } finally {
            updatingChoices = false;
        }
    }

    private static boolean sameId(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Narrows {@link #loaded} by the Category, Supplier and Status choices, and goes to page 1. */
    private void applyFilters() {
        Category category = categoryFilter.getValue();
        Supplier supplier = supplierFilter.getValue();
        StatusFilter status = statusFilter.getValue();
        List<Product> result = new ArrayList<>();
        for (Product p : loaded) {
            boolean categoryOk = category == null || category.categoryId() == null
                    || (category == NO_CATEGORY ? p.categoryId() == null
                                                : category.categoryId().equals(p.categoryId()));
            boolean supplierOk = supplier == null || supplier.supplierId() == null
                    || (supplier == NO_SUPPLIER ? p.supplierId() == null
                                                : supplier.supplierId().equals(p.supplierId()));
            boolean statusOk = status == null || status == StatusFilter.ALL
                    || (status == StatusFilter.ACTIVE) == p.active();
            if (categoryOk && supplierOk && statusOk) {
                result.add(p);
            }
        }
        filtered = result;
        pager.reset();
        showPage();
    }

    private void showPage() {
        pager.setTotal(filtered.size());
        table.getItems().setAll(pager.slice(filtered));
    }

    private Window owner() {
        return view.getScene() == null ? null : view.getScene().getWindow();
    }
}
