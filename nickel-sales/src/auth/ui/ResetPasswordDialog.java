package auth.ui;

import auth.account.AccountException;
import auth.account.PassPolicy;
import auth.contract.User;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

/**
 * "Reset Password" pop-up: an admin sets a new temporary password for another user.
 * Stays open until the submit callback succeeds or the admin cancels.
 */
final class ResetPasswordDialog {

    interface Submit {
        void accept(String password) throws AccountException;
    }

    private ResetPasswordDialog() { }

    static void show(Window owner, User target, Submit submit) {
        Modal modal = new Modal(owner, 660);
        boolean[] done = {false};

        PasswordField password = new PasswordField();
        password.setPromptText("New password");
        Styles.underline(password);
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Type it again");
        Styles.underline(confirm);

        GridPane grid = Modal.grid();
        int row = Modal.row(grid, 0, "New Password", password);
        Modal.row(grid, row, "Confirm Password", confirm);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button reset = new Button("Reset Password");
        Styles.modalButton(reset, Theme.INFORMATION, "white");
        reset.setDefaultButton(true);
        reset.setOnAction(e -> {
            if (!password.getText().equals(confirm.getText())) {
                error.setText("The two passwords do not match.");
                return;
            }
            try {
                submit.accept(password.getText());
                done[0] = true;
                modal.close();
            } catch (AccountException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        modal.card().getChildren().addAll(Modal.title("Reset Password"),
                Modal.subtitle("Set a temporary password for " + target.getFullName() + " (" + target.getLoginName()
                        + "). " + PassPolicy.REQUIREMENTS),
                grid, error, Modal.buttons(cancel, reset));
        modal.showAndWait();

        if (done[0]) {
            Modal.notice(owner, "Password Reset", "The password for " + target.getFullName() + " was reset.\n\n"
                    + "Give them the new password and ask them to change it after signing in.");
        }
    }
}
