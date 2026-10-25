package auth.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import auth.account.PassPolicy;
import auth.account.PasswordChangeException;
import auth.account.UserAccountService;

import java.util.Objects;
import shared.UiErrors;

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
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Change Password");
        dialog.setHeaderText(PassPolicy.REQUIREMENTS);

        PasswordField current = new PasswordField();
        current.setPromptText("Current password");
        PasswordField next = new PasswordField();
        next.setPromptText("New password");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Confirm new password");

        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #a61c1c;");

        VBox content = new VBox(10, current, next, confirm, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Change");
        // Consuming the event keeps the dialog open so the user can fix the input.
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                accounts.changePassword(sessionId, current.getText(), next.getText(), confirm.getText());
            } catch (PasswordChangeException e) {
                fail(e.getMessage(), error, current, next, confirm);
                event.consume();
            } catch (RuntimeException e) {
                fail(UiErrors.message(e, "Something went wrong. Try again, or contact the system administrator."),
                        error, current, next, confirm);
                event.consume();
            }
        });

        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                Alert done = new Alert(Alert.AlertType.INFORMATION, "Your password was changed.");
                done.initOwner(owner);
                done.showAndWait();
            }
        });
    }

    private void fail(String message, Label error, PasswordField current,
                      PasswordField next, PasswordField confirm) {
        error.setText(message);
        current.clear();
        next.clear();
        confirm.clear();
        current.requestFocus();
    }
}