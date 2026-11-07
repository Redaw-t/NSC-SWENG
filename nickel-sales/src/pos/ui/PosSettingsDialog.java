package pos.ui;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.UiErrors;

/**
 * Menu > POS Settings: this terminal's POS options (see {@link PosSettings}). Cancel [ESC] keeps
 * them as they were; Save [ENTER] keeps the changes on this computer.
 */
final class PosSettingsDialog {

    private PosSettingsDialog() { }

    /** @return true if the settings were saved */
    static boolean show(Window owner) {
        Modal modal = new Modal(owner, 620);
        boolean[] saved = {false};

        CheckBox autoPrint = new CheckBox("Print the receipt automatically after payment");
        autoPrint.setSelected(PosSettings.autoPrintReceipt());
        Styles.modalCheckBox(autoPrint);
        Label autoPrintNote = new Label("The receipt prints straight away with no preview. Only for new sales: "
                + "Reprint Receipt (F6) always shows the preview first.");
        autoPrintNote.setWrapText(true);
        Styles.muted(autoPrintNote);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = PosStyle.modalKeyButton("Cancel", "ESC", PosStyle.Kind.SECONDARY);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button save = PosStyle.modalKeyButton("Save", "ENTER", PosStyle.Kind.PRIMARY);
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            try {
                PosSettings.setAutoPrintReceipt(autoPrint.isSelected());
                saved[0] = true;
                modal.close();
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "The settings could not be saved. Try again."));
            }
        });

        modal.card().getChildren().addAll(Modal.title("POS Settings"),
                Modal.subtitle("Settings for this terminal only."),
                autoPrint, autoPrintNote, error, Modal.buttons(cancel, save));
        modal.showAndWait();
        return saved[0];
    }
}
