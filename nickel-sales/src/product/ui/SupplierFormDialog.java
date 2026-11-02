package product.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import product.model.Supplier;
import product.service.ProductException;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

/**
 * Add / edit supplier pop-up. Stays open until the submit callback succeeds or the user
 * cancels, so a refused value (duplicate name, bad email) can be fixed without retyping.
 */
final class SupplierFormDialog {

    interface Submit {
        void accept(String name, String contact, String phone, String email, String address) throws ProductException;
    }

    private SupplierFormDialog() { }

    /** @param existing null when adding a supplier */
    static void show(Window owner, Supplier existing, Submit submit) {
        boolean creating = existing == null;
        Modal modal = new Modal(owner, 700);

        TextField name = field(creating ? "" : existing.name(), "Supplier name");
        TextField contact = field(creating ? "" : nullToEmpty(existing.contactPerson()), "Optional");
        TextField phone = field(creating ? "" : nullToEmpty(existing.phone()), "Optional");
        TextField email = field(creating ? "" : nullToEmpty(existing.email()), "Optional");
        TextField address = field(creating ? "" : nullToEmpty(existing.address()), "Optional");

        GridPane grid = Modal.grid();
        int row = 0;
        row = Modal.row(grid, row, "Supplier Name", name);
        row = Modal.row(grid, row, "Contact Person", contact);
        row = Modal.row(grid, row, "Phone", phone);
        row = Modal.row(grid, row, "Email", email);
        Modal.row(grid, row, "Address", address);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button save = new Button(creating ? "Save Supplier" : "Save Changes");
        Styles.modalButton(save, Theme.INFORMATION, "white");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            try {
                submit.accept(name.getText(), contact.getText(), phone.getText(), email.getText(), address.getText());
                modal.close();
            } catch (ProductException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, ProductUi.FALLBACK));
            }
        });

        modal.card().getChildren().addAll(Modal.title(creating ? "Add Supplier" : "Edit Supplier"),
                Modal.subtitle("Only the name is required."), grid, error, Modal.buttons(cancel, save));
        modal.showAndWait();
    }

    private static TextField field(String text, String prompt) {
        TextField field = new TextField(text);
        field.setPromptText(prompt);
        Styles.underline(field);
        return field;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
