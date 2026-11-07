package pos.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;

/**
 * The POS "Are you sure?" pop-up (Cancel Transaction, Log Out). Same as shared {@link Modal#confirm},
 * but with the keys on the buttons like the other POS pop-ups: the grey button is [ESC], the red
 * one [ENTER].
 */
final class PosConfirm {

    private PosConfirm() { }

    /** True if the cashier pressed the red button (or Enter). */
    static boolean ask(Window owner, String title, String message, String confirmText, String cancelText) {
        Modal modal = new Modal(owner, 520);
        boolean[] confirmed = {false};

        Label body = new Label(message);
        Styles.modalLabel(body);
        body.setWrapText(true);

        Button cancel = PosStyle.modalKeyButton(cancelText, "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button confirm = PosStyle.modalKeyButton(confirmText, "ENTER", PosStyle.Kind.DANGER);
        confirm.setDefaultButton(true);
        confirm.setOnAction(e -> {
            confirmed[0] = true;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title(title), body, Modal.buttons(cancel, confirm));
        modal.showAndWait();
        return confirmed[0];
    }
}
