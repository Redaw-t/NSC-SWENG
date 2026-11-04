package barcode.ui;

import barcode.service.BarcodeRow;
import barcode.service.BarcodeService;
import barcode.service.EscPosLabelPrinter;
import barcode.service.LabelPrintException;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.UiErrors;

/**
 * The "Print Barcode Label" pop-up: the product, how many copies (1 to 100), which prices to print
 * (Retail / Wholesale tick boxes) and a preview of the label. The preview is the very bitmap that
 * is sent to the printer (see barcode.service.LabelRenderer), so the printed label looks exactly
 * like it; it redraws whenever a tick box changes. It stays open if the printer refuses, so the
 * user can fix the printer and press Print Label again.
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

        // Which prices go on the label. A product without a wholesale price can't print one.
        boolean hasWholesale = row.product().wholesalePrice() != null;
        CheckBox retail = new CheckBox("Retail price");
        retail.setSelected(true);
        Styles.modalCheckBox(retail);
        CheckBox wholesale = new CheckBox(hasWholesale ? "Wholesale price" : "Wholesale price (none set)");
        wholesale.setSelected(hasWholesale);
        wholesale.setDisable(!hasWholesale);
        Styles.modalCheckBox(wholesale);
        HBox prices = new HBox(24, retail, wholesale);
        prices.setAlignment(Pos.CENTER_LEFT);

        StackPane previewSlot = new StackPane();
        Runnable redraw = () -> {
            Node preview;
            try {
                preview = BarcodeUi.labelPreview(service.previewLabel(row.product(), retail.isSelected(),
                        wholesale.isSelected()));
            } catch (RuntimeException e) {
                preview = BarcodeUi.labelPreview(null);
            }
            previewSlot.getChildren().setAll(preview);
            modal.sizeToFit();
        };
        retail.selectedProperty().addListener((o, was, is) -> redraw.run());
        wholesale.selectedProperty().addListener((o, was, is) -> redraw.run());
        redraw.run();

        Label previewNote = new Label("Actual preview of the Barcode Label.");
        Styles.muted(previewNote);
        previewNote.setMaxWidth(Double.MAX_VALUE);
        previewNote.setAlignment(Pos.CENTER);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

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
                service.printLabels(row.product(), count, retail.isSelected(), wholesale.isSelected());
                printed[0] = count;
                modal.close();
            } catch (LabelPrintException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        HBox top = new HBox(24,
                BarcodeUi.labelled("Product", BarcodeUi.valueBox(row.product().name())),
                BarcodeUi.labelled("Copies", copies));
        HBox.setHgrow(top.getChildren().get(0), Priority.ALWAYS);
        VBox form = new VBox(16, top,
                BarcodeUi.labelled("Prices to Print", prices),
                new VBox(8, BarcodeUi.fieldLabel("Label Preview"), previewSlot, previewNote),
                error);
        modal.card().getChildren().addAll(Modal.title("Print Barcode Label"), form, Modal.buttons(cancel, print));
        modal.showAndWait();
        return printed[0];
    }
}
