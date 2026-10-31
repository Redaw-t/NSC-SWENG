package product.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import product.model.Supplier;
import product.service.ProductException;
import product.service.SupplierService;
import shared.UiErrors;

import java.util.function.Function;

/**
 * Supplier list: add, edit, deactivate / reactivate. Suppliers are never deleted;
 * a deactivated one is only hidden from the product form. Each action goes through
 * SupplierService (permission check, rules, audit log).
 */
final class SupplierDialog {

    private interface Action {
        void run() throws ProductException;
    }

    private interface Submit {
        void accept(String name, String contact, String phone, String email, String address) throws ProductException;
    }

    private final SupplierService service;
    private final ActorSource actor;
    private final TableView<Supplier> table = new TableView<>();
    private final Label message = new Label();
    private final Dialog<ButtonType> dialog = new Dialog<>();

    private SupplierDialog(SupplierService service, ActorSource actor) {
        this.service = service;
        this.actor = actor;
    }

    static void show(Window owner, SupplierService service, ActorSource actor) {
        new SupplierDialog(service, actor).open(owner);
    }

    private void open(Window owner) {
        dialog.initOwner(owner);
        dialog.setTitle("Suppliers");
        dialog.setHeaderText("Product suppliers");

        table.getColumns().add(column("Name", 190, Supplier::name));
        table.getColumns().add(column("Contact", 130, s -> nullToEmpty(s.contactPerson())));
        table.getColumns().add(column("Phone", 110, s -> nullToEmpty(s.phone())));
        table.getColumns().add(column("Email", 170, s -> nullToEmpty(s.email())));
        table.getColumns().add(column("Status", 90, s -> s.active() ? "Active" : "Deactivated"));
        table.setPlaceholder(new Label("No suppliers yet."));
        table.setPrefWidth(720);
        table.setPrefHeight(280);

        Button add = new Button("Add supplier");
        Button edit = new Button("Edit");
        Button toggle = new Button("Deactivate");
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        toggle.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, s) ->
                toggle.setText(s != null && !s.active() ? "Reactivate" : "Deactivate"));
        add.setOnAction(e -> {
            showForm(null, (name, contact, phone, email, address) ->
                    service.createSupplier(actor.current(), name, contact, phone, email, address));
            reload();
        });
        edit.setOnAction(e -> {
            Supplier selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            showForm(selected, (name, contact, phone, email, address) ->
                    service.updateSupplier(actor.current(), selected.supplierId(), name, contact, phone, email, address));
            reload();
        });
        toggle.setOnAction(e -> {
            Supplier selected = table.getSelectionModel().getSelectedItem();
            attempt(() -> service.setActive(actor.current(), selected.supplierId(), !selected.active()));
        });

        message.setWrapText(true);
        message.setMaxWidth(700);
        message.setStyle("-fx-text-fill: #a61c1c;");

        VBox content = new VBox(10, table, new HBox(8, add, edit, toggle), message);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        reload();
        dialog.showAndWait();
    }

    /** The add / edit form. Stays open until the submit succeeds or the user cancels. */
    private void showForm(Supplier existing, Submit submit) {
        boolean creating = existing == null;
        Dialog<ButtonType> form = new Dialog<>();
        form.initOwner(dialog.getDialogPane().getScene().getWindow());
        form.setTitle(creating ? "Add Supplier" : "Edit Supplier");
        form.setHeaderText("Only the name is required.");

        TextField name = new TextField(creating ? "" : existing.name());
        TextField contact = new TextField(creating ? "" : nullToEmpty(existing.contactPerson()));
        TextField phone = new TextField(creating ? "" : nullToEmpty(existing.phone()));
        TextField email = new TextField(creating ? "" : nullToEmpty(existing.email()));
        TextField address = new TextField(creating ? "" : nullToEmpty(existing.address()));
        name.setPromptText("Supplier name");
        contact.setPromptText("Optional");
        phone.setPromptText("Optional");
        email.setPromptText("Optional");
        address.setPromptText("Optional");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Contact person"), contact);
        grid.addRow(2, new Label("Phone"), phone);
        grid.addRow(3, new Label("Email"), email);
        grid.addRow(4, new Label("Address"), address);
        name.setPrefWidth(280);

        Label error = new Label();
        error.setWrapText(true);
        error.setMaxWidth(420);
        error.setStyle("-fx-text-fill: #a61c1c;");
        VBox content = new VBox(10, grid, error);
        content.setPadding(new Insets(10));
        form.getDialogPane().setContent(content);
        form.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button ok = (Button) form.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText(creating ? "Create" : "Save");
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                submit.accept(name.getText(), contact.getText(), phone.getText(), email.getText(), address.getText());
            } catch (ProductException e) {
                error.setText(e.getMessage());
                event.consume();
            } catch (RuntimeException e) {
                error.setText(UiErrors.message(e, "Something went wrong. Try again, or contact the system administrator."));
                event.consume();
            }
        });
        form.showAndWait();
    }

    private void attempt(Action action) {
        try {
            action.run();
        } catch (ProductException e) {
            message.setText(e.getMessage());
            return;
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, "Something went wrong. Try again, or contact the system administrator."));
            return;
        }
        reload();
    }

    private void reload() {
        try {
            Supplier keep = table.getSelectionModel().getSelectedItem();
            table.getItems().setAll(service.listSuppliers(actor.current(), true));
            if (keep != null) {
                table.getItems().stream().filter(s -> s.supplierId().equals(keep.supplierId()))
                        .findFirst().ifPresent(s -> table.getSelectionModel().select(s));
            }
            message.setText("");
        } catch (ProductException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, "Something went wrong. Try again, or contact the system administrator."));
        }
    }

    private static TableColumn<Supplier, String> column(String title, double width, Function<Supplier, String> value) {
        TableColumn<Supplier, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        col.setPrefWidth(width);
        return col;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
