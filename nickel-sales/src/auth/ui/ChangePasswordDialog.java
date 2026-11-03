package auth.ui;

import auth.account.IncorrectCurrentPasswordException;
import auth.account.PassPolicy;
import auth.account.PasswordChangeException;
import auth.account.UserAccountService;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.util.Objects;

/**
 * "Change Password" pop-up: a user changes their own password. Drawn like the Reset Password
 * pop-up in user management (shared {@link Modal}: white card, dimmed app behind). Stays open
 * until the password is changed or the user cancels. The reason for a failed attempt shows in
 * red, and only the field(s) to retype are cleared:
 * - a field left empty: nothing is submitted, no field is cleared;
 * - new and confirm passwords differ, or the new one is not accepted: new and confirm are cleared;
 * - the current password is wrong: only the current password is cleared.
 */
public class ChangePasswordDialog {

    private final Window owner;
    private final UserAccountService accounts;
    private final String sessionId;

    public ChangePasswordDialog(Window owner, UserAccountService accounts, String sessionId) {
        this.owner = owner;
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public void show() {
        Modal modal = new Modal(owner, 660);
        boolean[] done = {false};

        PasswordField current = new PasswordField();
        current.setPromptText("Current password");
        Styles.underline(current);
        PasswordField next = new PasswordField();
        next.setPromptText("New password");
        Styles.underline(next);
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Type it again");
        Styles.underline(confirm);

        GridPane grid = Modal.grid();
        int row = Modal.row(grid, 0, "Current Password", current);
        row = Modal.row(grid, row, "New Password", next);
        Modal.row(grid, row, "Confirm Password", confirm);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button change = new Button("Change Password");
        Styles.modalButton(change, Theme.INFORMATION, "white");
        change.setDefaultButton(true);
        change.setOnAction(e -> {
            if (current.getText().isEmpty() || next.getText().isEmpty() || confirm.getText().isEmpty()) {
                error.setText("Fill in all password fields.");   // not submitted; keep what was typed
                return;
            }
            if (!next.getText().equals(confirm.getText())) {
                retype(error, "New password and confirmation do not match.", next, confirm);
                return;
            }
            try {
                accounts.changePassword(sessionId, current.getText(), next.getText(), confirm.getText());
                done[0] = true;
                modal.close();
            } catch (IncorrectCurrentPasswordException ex) {
                retype(error, ex.getMessage(), current);
            } catch (PasswordChangeException ex) {
                retype(error, ex.getMessage(), next, confirm);   // e.g. the new password breaks the rules
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex,
                        "Something went wrong. Try again, or contact the system administrator."));
            }
        });

        modal.card().getChildren().addAll(Modal.title("Change Password"),
                Modal.subtitle(PassPolicy.REQUIREMENTS),
                grid, error, Modal.buttons(cancel, change));
        Platform.runLater(current::requestFocus);
        modal.showAndWait();

        if (done[0]) {
            Modal.notice(owner, "Password Changed", "Your password was changed.");
        }
    }

    /** Shows {@code message}, clears {@code fields} and puts the cursor in the first of them. */
    private static void retype(Label error, String message, PasswordField... fields) {
        error.setText(message);
        for (PasswordField field : fields) {
            field.clear();
        }
        fields[0].requestFocus();
    }
}
