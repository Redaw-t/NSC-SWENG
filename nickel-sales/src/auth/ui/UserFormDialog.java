package auth.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import auth.account.AccountException;
import auth.account.PassPolicy;
import auth.contract.User;
import auth.contract.UserRole;
import shared.UiErrors;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Add/edit form. Stays open until the submit callback succeeds or the user cancels. */
final class UserFormDialog {

    record Form(String firstName, String lastName, Set<UserRole> roles, String password) { }

    interface Submit {
        /** @return a notice to show after success (e.g. the generated username), or null. */
        String accept(Form form) throws AccountException;
    }

    private UserFormDialog() { }

    /** {@code existing} is null when adding a user; the password field only appears then. */
    static void show(Window owner, User existing, Submit submit) {
        boolean creating = existing == null;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(creating ? "Add User" : "Edit User");
        if (creating) {
            dialog.setHeaderText("Set a temporary password. " + PassPolicy.REQUIREMENTS);
        }

        TextField first = new TextField(creating ? "" : existing.getFirstName());
        first.setPromptText("First name");
        TextField last = new TextField(creating ? "" : existing.getLastName());
        last.setPromptText("Last name");
        PasswordField password = new PasswordField();
        password.setPromptText("Temporary password");

        Map<UserRole, CheckBox> roleBoxes = new EnumMap<>(UserRole.class);
        HBox roleRow = new HBox(12);
        for (UserRole role : UserRole.values()) {
            CheckBox box = new CheckBox(label(role));
            box.setSelected(!creating && existing.hasRole(role));
            roleBoxes.put(role, box);
            roleRow.getChildren().add(box);
        }

        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #a61c1c;");

        VBox content = new VBox(10, first, last, new Label("Roles"), roleRow);
        if (creating) {
            content.getChildren().add(password);
        }
        content.getChildren().add(error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        AtomicReference<String> notice = new AtomicReference<>();
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText(creating ? "Create" : "Save");
        // Consuming the event keeps the dialog open so the admin can fix the input.
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
            roleBoxes.forEach((role, box) -> {
                if (box.isSelected()) {
                    roles.add(role);
                }
            });
            try {
                notice.set(submit.accept(new Form(first.getText(), last.getText(), roles,
                        creating ? password.getText() : null)));
            } catch (AccountException e) {
                error.setText(e.getMessage());
                event.consume();
            } catch (RuntimeException e) {
                error.setText(UiErrors.message(e, "Something went wrong. Try again, or contact the system administrator."));
                event.consume();
            }
        });

        dialog.showAndWait();

        if (notice.get() != null) {
            Alert done = new Alert(Alert.AlertType.INFORMATION, notice.get());
            done.initOwner(owner);
            done.showAndWait();
        }
    }

    private static String label(UserRole role) {
        return switch (role) {
            case ADMIN -> "Admin";
            case CASHIER -> "Cashier";
            case INVENTORY_STAFF -> "Inventory";
        };
    }
}
