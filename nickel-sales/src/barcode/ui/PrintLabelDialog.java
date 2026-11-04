package barcode.ui;

import barcode.service.BarcodeRow;
import barcode.service.BarcodeService;
import barcode.service.EscPosLabelPrinter;
import barcode.service.LabelPrintException;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.UiErrors;

/**
 * The "Print Barcode Label" pop-up: the product, its barcode, how many copies (1 to 100) and a
 * preview of the label. It stays open if the printer refuses, so the user can fix the printer
 * and press Print Label again.
 */
final class PrintLabelDialog {

    private PrintLabelDialog() { }

    /** @return the number of labels sent to the printer, or 0 if nothing was printed */
    static int show(Window owner, BarcodeService service, BarcodeRow row) {
        Modal modal = new Modal(owner, 700);
        int[] printed = {0};

        TextField copies = BarcodeUi.input("1");
        copies.setText("1");
        copies.setPrefWidth(90);
        copies.setMaxWidth(90);
        copies.setStyle(copies.getStyle() + " -fx-alignment: CENTER;");

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        HBox bottom = new HBox(40,
                BarcodeUi.labelled("Copies", copies),
                BarcodeUi.labelled("Label Preview", BarcodeUi.preview(row.barcode())));

        Button cancel = BarcodeUi.greyButton("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button print = BarcodeUi.blueButton("Print Label");
        print.setDefaultButton(true);
        print.setOnAction(e -> {
            error.setText("");
            int count;
            try {
                count = Integer.parseInt(copies.getText().trim());
            } catch (NumberFormatException ex) {
                error.setText("Copies must be a whole number from 1 to " + EscPosLabelPrinter.MAX_COPIES + ".");
                return;
            }
            try {
                service.printLabels(row.product(), count);
                printed[0] = count;
                modal.close();
            } catch (LabelPrintException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        VBox form = new VBox(16,
                BarcodeUi.labelled("Product", BarcodeUi.valueBox(row.product().name())),
                BarcodeUi.labelled("Barcode", BarcodeUi.valueBox(row.barcode())),
                bottom, error);
        modal.card().getChildren().addAll(Modal.title("Print Barcode Label"), form, Modal.buttons(cancel, print));
        modal.showAndWait();
        return printed[0];
    }
}
