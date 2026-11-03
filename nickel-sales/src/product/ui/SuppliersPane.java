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
import product.model.Supplier;
import product.service.ProductException;
import product.service.SupplierService;
import shared.Modal;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.UiErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Suppliers" tab: add, edit, deactivate / reactivate. Suppliers are never deleted;
 * a deactivated one is only hidden from the add / edit product form. Each action goes
 * through SupplierService (permission check, rules, audit log).
 */
final class SuppliersPane {

    private interface Action {
        void run() throws ProductException;
    }

    private final SupplierService service;
    private final ActorSource actor;

    private final TextField search = new TextField();
    private final TableView<Supplier> table = Tables.create();
    private final TablePager pager = TablePager.links(this::showPage);
    private final Label message = new Label();
    private final VBox view = new VBox(16);

    private List<Supplier> loaded = List.of();
    private List<Supplier> filtered = List.of();

    SuppliersPane(SupplierService service, ActorSource actor) {
        this.service = service;
        this.actor = actor;

        table.getColumns().add(Tables.<Supplier>numberColumn(pager));
        table.getColumns().add(Tables.<Supplier>textColumn("Supplier", 170, Supplier::name));
        table.getColumns().add(Tables.<Supplier>textColumn("Contact Person", 130, s -> blank(s.contactPerson())));
        table.getColumns().add(Tables.<Supplier>textColumn("Phone", 110, s -> blank(s.phone())));
        table.getColumns().add(Tables.<Supplier>textColumn("Email", 170, s -> blank(s.email())));
        table.getColumns().add(Tables.<Supplier>textColumn("Address", 200, s -> blank(s.address())));
        table.getColumns().add(Tables.<Supplier>nodeColumn("Status", 90, 90, s -> ProductUi.pill(s.active())));
        table.getColumns().add(Tables.<Supplier>nodeColumn("Actions", 220, 200, this::actions));
        table.setPlaceholder(new Label("No suppliers found."));
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        search.setPromptText("Search Supplier...");
        search.setPrefWidth(300);
        Styles.input(search);
        search.textProperty().addListener((obs, old, text) -> applyFilter());

        Button add = ProductUi.primaryButton("+ Add Supplier");
        add.setOnAction(e -> addSupplier());
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

    private Node actions(Supplier s) {
        Button edit = ProductUi.rowButton("Edit", ProductUi.RowColour.EDIT);
        edit.setOnAction(e -> editSupplier(s));
        Button toggle = ProductUi.toggleButton(s.active());
        toggle.setOnAction(e -> toggle(s));
        HBox box = new HBox(8, edit, toggle);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void addSupplier() {
        SupplierFormDialog.show(owner(), null, (name, contact, phone, email, address) ->
                service.createSupplier(actor.current(), name, contact, phone, email, address));
        reload();
    }

    private void editSupplier(Supplier s) {
        SupplierFormDialog.show(owner(), s, (name, contact, phone, email, address) ->
                service.updateSupplier(actor.current(), s.supplierId(), name, contact, phone, email, address));
        reload();
    }

    private void toggle(Supplier s) {
        if (s.active() && !Modal.confirm(owner(), "Deactivate supplier",
                "Deactivate \"" + s.name() + "\"?\n\nIt will no longer be offered when adding or editing a product. "
                        + "Products already linked to it keep it.", "Deactivate")) {
            return;
        }
        attempt(() -> service.setActive(actor.current(), s.supplierId(), !s.active()));
    }

    private void attempt(Action action) {
        try {
            action.run();
        } catch (ProductException e) {
            message.setText(e.getMessage());
            return;
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e));
            return;
        }
        reload();
    }

    private void reload() {
        try {
            loaded = service.listSuppliers(actor.current(), true);
            applyFilter();
            message.setText("");
        } catch (ProductException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e));
        }
    }

    private void applyFilter() {
        String text = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Supplier> result = new ArrayList<>();
        for (Supplier s : loaded) {
            String haystack = (s.name() + " " + blank(s.contactPerson()) + " " + blank(s.email()) + " " + blank(s.address()))
                    .toLowerCase(Locale.ROOT);
            if (text.isEmpty() || haystack.contains(text)) {
                result.add(s);
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

    private static String blank(String text) {
        return text == null ? "" : text;
    }

    private Window owner() {
        return view.getScene() == null ? null : view.getScene().getWindow();
    }
}
