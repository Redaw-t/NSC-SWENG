package product.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import product.service.ProductException;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

/** A pop-up with one text field: adding or renaming a category. Stays open until it is accepted or cancelled. */
final class NameDialog {

    interface Submit {
        void accept(String text) throws ProductException;
    }

    private NameDialog() { }

    /**
     * @param title      e.g. "Add Category"
     * @param fieldLabel e.g. "Category Name"
     * @param initial    the text to start with ("" when adding)
     */
    static void show(Window owner, String title, String fieldLabel, String initial, Submit submit) {
        Modal modal = new Modal(owner, 560);

        TextField field = new TextField(initial);
        Styles.underline(field);
        GridPane grid = Modal.grid();
        Modal.row(grid, 0, fieldLabel, field);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button save = new Button("Save");
        Styles.modalButton(save, Theme.INFORMATION, "white");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            try {
                submit.accept(field.getText());
                modal.close();
            } catch (ProductException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        modal.card().getChildren().addAll(Modal.title(title), grid, error, Modal.buttons(cancel, save));
        modal.showAndWait();
    }
}
