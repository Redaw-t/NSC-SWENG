package product.ui;

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
import product.service.CategoryService;
import product.service.ProductException;
import shared.Styles;
import shared.UiErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Categories" tab: add, rename, deactivate / reactivate. Categories are never deleted;
 * a deactivated one is only hidden from the add / edit product form. Each action goes
 * through CategoryService (permission check, rules, audit log).
 */
final class CategoriesPane {

    private interface Action {
        void run() throws ProductException;
    }

    private final CategoryService service;
    private final ActorSource actor;

    private final TextField search = new TextField();
    private final TableView<Category> table = ProductUi.table();
    private final Pager pager = new Pager(this::showPage);
    private final Label message = new Label();
    private final VBox view = new VBox(16);

    private List<Category> loaded = List.of();
    private List<Category> filtered = List.of();

    CategoriesPane(CategoryService service, ActorSource actor) {
        this.service = service;
        this.actor = actor;

        table.getColumns().add(ProductUi.<Category>numberColumn(pager));
        table.getColumns().add(ProductUi.<Category>textColumn("Category", 400, Category::name));
        table.getColumns().add(ProductUi.<Category>nodeColumn("Status", 120, 100, c -> ProductUi.pill(c.active())));
        table.getColumns().add(ProductUi.<Category>nodeColumn("Actions", 260, 210, this::actions));
        table.setPlaceholder(new Label("No categories found."));
        ProductUi.fitPageToHeight(table, pager, this::showPage);

        search.setPromptText("Search Category...");
        search.setPrefWidth(300);
        Styles.input(search);
        search.textProperty().addListener((obs, old, text) -> applyFilter());

        Button add = ProductUi.primaryButton("+ Add Category");
        add.setOnAction(e -> addCategory());
        HBox toolbar = new HBox(12, search, add);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        message.setWrapText(true);
        Styles.error(message);

        view.getChildren().addAll(toolbar, table, message, pager.getView());
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    Parent getView() {
        return view;
    }

    void refresh() {
        reload();
    }

    private Node actions(Category c) {
        Button rename = ProductUi.rowButton("Rename", ProductUi.RowColour.EDIT);
        rename.setOnAction(e -> rename(c));
        Button toggle = ProductUi.toggleButton(c.active());
        toggle.setOnAction(e -> toggle(c));
        HBox box = new HBox(8, rename, toggle);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void addCategory() {
        NameDialog.show(owner(), "Add Category", "Category Name", "",
                text -> service.createCategory(actor.current(), text));
        reload();
    }

    private void rename(Category c) {
        NameDialog.show(owner(), "Rename Category", "Category Name", c.name(),
                text -> service.renameCategory(actor.current(), c.categoryId(), text));
        reload();
    }

    private void toggle(Category c) {
        if (c.active() && !ProductUi.confirm(owner(), "Deactivate category",
                "Deactivate \"" + c.name() + "\"?\n\nIt will no longer be offered when adding or editing a product. "
                        + "Products already in it keep it.", "Deactivate")) {
            return;
        }
        attempt(() -> service.setActive(actor.current(), c.categoryId(), !c.active()));
    }

    private void attempt(Action action) {
        try {
            action.run();
        } catch (ProductException e) {
            message.setText(e.getMessage());
            return;
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, ProductUi.FALLBACK));
            return;
        }
        reload();
    }

    private void reload() {
        try {
            loaded = service.listCategories(actor.current(), true);
            applyFilter();
            message.setText("");
        } catch (ProductException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, ProductUi.FALLBACK));
        }
    }

    private void applyFilter() {
        String text = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Category> result = new ArrayList<>();
        for (Category c : loaded) {
            if (text.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(text)) {
                result.add(c);
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
