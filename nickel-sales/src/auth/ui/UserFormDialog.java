package auth.ui;

import auth.account.AccountException;
import auth.account.PassPolicy;
import auth.contract.User;
import auth.contract.UserRole;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Add / Edit User pop-up. Stays open until the submit callback succeeds or the user cancels. */
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
        Modal modal = new Modal(owner, 680);
        String[] notice = {null};

        TextField first = field(creating ? "" : existing.getFirstName(), "First name");
        TextField last = field(creating ? "" : existing.getLastName(), "Last name");
        PasswordField password = new PasswordField();
        password.setPromptText("Temporary password");
        Styles.underline(password);

        Map<UserRole, CheckBox> roleBoxes = new EnumMap<>(UserRole.class);
        HBox roleRow = new HBox(18);
        for (UserRole role : UserRole.values()) {
            CheckBox box = new CheckBox(UserUi.roleLabel(role));
            Styles.modalCheckBox(box);
            box.setSelected(!creating && existing.hasRole(role));
            roleBoxes.put(role, box);
            roleRow.getChildren().add(box);
        }

        GridPane grid = Modal.grid();
        int row = 0;
        row = Modal.row(grid, row, "First Name", first);
        row = Modal.row(grid, row, "Last Name", last);
        row = Modal.row(grid, row, "Roles", roleRow);
        if (creating) {
            Modal.row(grid, row, "Temporary Password", password);
        }

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button save = new Button(creating ? "Save User" : "Save Changes");
        Styles.modalButton(save, Theme.INFORMATION, "white");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
            roleBoxes.forEach((role, box) -> {
                if (box.isSelected()) {
                    roles.add(role);
                }
            });
            try {
                notice[0] = submit.accept(new Form(first.getText(), last.getText(), roles,
                        creating ? password.getText() : null));
                modal.close();
            } catch (AccountException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        modal.card().getChildren().add(Modal.title(creating ? "Add User" : "Edit User"));
        if (creating) {
            modal.card().getChildren().add(Modal.subtitle("Set a temporary password. " + PassPolicy.REQUIREMENTS));
        }
        modal.card().getChildren().addAll(grid, error, Modal.buttons(cancel, save));
        modal.showAndWait();

        if (notice[0] != null) {
            Modal.notice(owner, "User Created", notice[0]);
        }
    }

    private static TextField field(String text, String prompt) {
        TextField field = new TextField(text);
        field.setPromptText(prompt);
        Styles.underline(field);
        return field;
    }
}
