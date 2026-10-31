package product.ui;

import app.ScreenContext;
import auth.contract.User;
import auth.permission.Permission;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import product.model.Category;
import product.model.Product;
import product.model.Supplier;
import product.service.CategoryService;
import product.service.ProductException;
import product.service.ProductService;
import product.service.SupplierService;
import shared.Money;
import shared.Quantity;
import shared.UiErrors;

import java.util.List;
import java.util.function.Function;

/**
 * Product management: search the catalog, add, edit and discontinue products, and open
 * the category and supplier lists. Shows prices BEFORE VAT, as POS does. Stock is shown
 * but cannot be edited here (Inventory owns it). Every action goes through the services,
 * which check the permission again and write the audit log.
 */
public class ProductScreen {

    private static final String FALLBACK = "Something went wrong. Try again, or contact the system administrator.";

    private interface Action {
        void run() throws ProductException;
    }

    private final ScreenContext screen;
    private final ProductService products;
    private final CategoryService categories;
    private final SupplierService suppliers;
    private final ActorSource actor;

    private final TableView<Product> table = new TableView<>();
    private final TextField search = new TextField();
    private final CheckBox showInactive = new CheckBox("Show discontinued");
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();

    public ProductScreen(ScreenContext screen, ProductService products, CategoryService categories,
                         SupplierService suppliers) {
        this.screen = screen;
        this.products = products;
        this.categories = categories;
        this.suppliers = suppliers;
        this.actor = () -> screen.app().auth().getCurrentUser(screen.sessionId());

        table.getColumns().add(column("ID", 80, Product::productId));
        table.getColumns().add(column("Barcode", 130, p -> p.barcode() == null ? "" : p.barcode()));
        table.getColumns().add(column("Name", 220, Product::name));
        table.getColumns().add(column("Unit", 60, Product::unit));
        table.getColumns().add(rightColumn("Retail", 90, p -> Money.format(p.retailPrice())));
        table.getColumns().add(rightColumn("Wholesale", 90,
                p -> p.wholesalePrice() == null ? "" : Money.format(p.wholesalePrice())));
        table.getColumns().add(column("VAT", 60, p -> p.vatable() ? "12%" : "Exempt"));
        table.getColumns().add(column("Category", 130, p -> p.categoryName() == null ? "" : p.categoryName()));
        table.getColumns().add(column("Supplier", 150, p -> p.supplierName() == null ? "" : p.supplierName()));
        table.getColumns().add(rightColumn("Stock", 80, p -> Quantity.format(p.stockOnHand())));
        table.getColumns().add(column("Status", 90, p -> p.active() ? "Active" : "Discontinued"));
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && !item.active() ? "-fx-opacity: 0.55;" : "");
            }
        });
        table.setPlaceholder(new Label("No products found."));

        search.setPromptText("Search by name or barcode");
        search.setPrefWidth(320);
        search.textProperty().addListener((obs, old, text) -> reload(false));
        showInactive.setOnAction(e -> reload(false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button categoriesButton = new Button("Categories");
        Button suppliersButton = new Button("Suppliers");
        categoriesButton.setOnAction(e -> {
            CategoryDialog.show(owner(), categories, actor);
            reload(true);
        });
        suppliersButton.setOnAction(e -> {
            SupplierDialog.show(owner(), suppliers, actor);
            reload(true);
        });
        HBox filters = new HBox(12, search, showInactive, spacer, categoriesButton, suppliersButton);
        filters.setAlignment(Pos.CENTER_LEFT);

        Button add = new Button("Add product");
        Button edit = new Button("Edit");
        Button toggle = new Button("Discontinue");
        Button back = new Button("Back");
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        toggle.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, p) ->
                toggle.setText(p != null && !p.active() ? "Reactivate" : "Discontinue"));
        add.setOnAction(e -> addProduct());
        edit.setOnAction(e -> editSelected());
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                editSelected();
            }
        });
        toggle.setOnAction(e -> toggleSelected());
        back.setOnAction(e -> screen.goHome());

        Label heading = new Label("Products");
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label hint = new Label("Prices are before VAT. Stock is changed by Inventory, not here.");
        hint.setStyle("-fx-text-fill: #666;");
        message.setWrapText(true);
        message.setStyle("-fx-text-fill: #a61c1c;");

        view.setTop(new VBox(8, heading, hint, filters));
        BorderPane.setMargin(table, new Insets(12, 0, 12, 0));
        view.setCenter(table);
        view.setBottom(new VBox(10, message, new HBox(12, add, edit, toggle, back)));
        view.setPadding(new Insets(24));
        view.setStyle("-fx-background-color: white;");

        reload(false);   // if it fails the message shows and Back still works
    }

    public Parent getView() {
        return view;
    }

    /* ------------------------------- actions ------------------------------- */

    private void addProduct() {
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
            message.setText(UiErrors.message(e, FALLBACK));
            return;
        }
        ProductFormDialog.show(owner(), null, cats, sups, draft -> products.createProduct(actor.current(), draft));
        reload(true);
    }

    private void editSelected() {
        Product selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
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
            message.setText(UiErrors.message(e, FALLBACK));
            return;
        }
        ProductFormDialog.show(owner(), selected, cats, sups,
                draft -> products.updateProduct(actor.current(), selected.productId(), draft));
        reload(true);
    }

    private void toggleSelected() {
        Product selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        attempt(() -> products.setActive(actor.current(), selected.productId(), !selected.active()));
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
            message.setText(UiErrors.message(e, FALLBACK));
            return;
        }
        reload(true);
    }

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
            Alert notice = new Alert(Alert.AlertType.INFORMATION,
                    "You can no longer manage products.\n\nYou will be returned to the home screen.");
            notice.setHeaderText("Access changed");
            notice.initOwner(owner());
            notice.showAndWait();
            screen.goHome();
            return;
        }
        try {
            Product keep = table.getSelectionModel().getSelectedItem();
            table.getItems().setAll(products.listProducts(current, search.getText(), showInactive.isSelected()));
            reselect(keep);
            message.setText("");
        } catch (ProductException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, FALLBACK));
        }
    }

    /** Keeps the same product selected after a reload (the table holds fresh objects). */
    private void reselect(Product previous) {
        if (previous == null) {
            return;
        }
        for (Product p : table.getItems()) {
            if (p.productId().equals(previous.productId())) {
                table.getSelectionModel().select(p);
                return;
            }
        }
    }

    private Window owner() {
        return view.getScene() == null ? null : view.getScene().getWindow();
    }

    private static TableColumn<Product, String> column(String title, double width, Function<Product, String> value) {
        TableColumn<Product, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    private static TableColumn<Product, String> rightColumn(String title, double width, Function<Product, String> value) {
        TableColumn<Product, String> col = column(title, width, value);
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }
}
