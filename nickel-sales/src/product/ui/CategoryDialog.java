package product.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import product.model.Category;
import product.service.CategoryService;
import product.service.ProductException;
import shared.UiErrors;

import java.util.Optional;

/**
 * Category list: add, rename, deactivate / reactivate. Categories are never deleted;
 * a deactivated one is only hidden from the product form. Each action goes through
 * CategoryService (permission check, rules, audit log).
 */
final class CategoryDialog {

    private interface Action {
        void run() throws ProductException;
    }

    private final CategoryService service;
    private final ActorSource actor;
    private final TableView<Category> table = new TableView<>();
    private final Label message = new Label();
    private final Dialog<ButtonType> dialog = new Dialog<>();

    private CategoryDialog(CategoryService service, ActorSource actor) {
        this.service = service;
        this.actor = actor;
    }

    static void show(Window owner, CategoryService service, ActorSource actor) {
        new CategoryDialog(service, actor).open(owner);
    }

    private void open(Window owner) {
        dialog.initOwner(owner);
        dialog.setTitle("Categories");
        dialog.setHeaderText("Product categories");

        TableColumn<Category, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        name.setPrefWidth(260);
        TableColumn<Category, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().active() ? "Active" : "Deactivated"));
        status.setPrefWidth(110);
        table.getColumns().add(name);
        table.getColumns().add(status);
        table.setPlaceholder(new Label("No categories yet."));
        table.setPrefHeight(260);

        TextField newName = new TextField();
        newName.setPromptText("New category name");
        HBox.setHgrow(newName, Priority.ALWAYS);
        Button add = new Button("Add");
        add.setDefaultButton(true);
        add.setOnAction(e -> attempt(() -> {
            service.createCategory(actor.current(), newName.getText());
            newName.clear();
        }));
        HBox addRow = new HBox(8, newName, add);
        addRow.setAlignment(Pos.CENTER_LEFT);

        Button rename = new Button("Rename");
        Button toggle = new Button("Deactivate");
        rename.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        toggle.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, c) ->
                toggle.setText(c != null && !c.active() ? "Reactivate" : "Deactivate"));
        rename.setOnAction(e -> renameSelected());
        toggle.setOnAction(e -> {
            Category selected = table.getSelectionModel().getSelectedItem();
            attempt(() -> service.setActive(actor.current(), selected.categoryId(), !selected.active()));
        });

        message.setWrapText(true);
        message.setMaxWidth(420);
        message.setStyle("-fx-text-fill: #a61c1c;");

        VBox content = new VBox(10, addRow, table, new HBox(8, rename, toggle), message);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        reload();
        dialog.showAndWait();
    }

    private void renameSelected() {
        Category selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        TextInputDialog ask = new TextInputDialog(selected.name());
        ask.initOwner(dialog.getDialogPane().getScene().getWindow());
        ask.setTitle("Rename category");
        ask.setHeaderText("New name for \"" + selected.name() + "\"");
        Optional<String> answer = ask.showAndWait();
        answer.ifPresent(text -> attempt(() -> service.renameCategory(actor.current(), selected.categoryId(), text)));
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
            Category keep = table.getSelectionModel().getSelectedItem();
            table.getItems().setAll(service.listCategories(actor.current(), true));
            if (keep != null) {
                table.getItems().stream().filter(c -> c.categoryId().equals(keep.categoryId()))
                        .findFirst().ifPresent(c -> table.getSelectionModel().select(c));
            }
            message.setText("");
        } catch (ProductException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, "Something went wrong. Try again, or contact the system administrator."));
        }
    }
}
