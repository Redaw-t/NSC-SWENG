package barcode.ui;

import barcode.service.BarcodeRow;
import barcode.service.BarcodeService;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import product.contract.BarcodeAssignException;
import shared.Modal;
import shared.SearchPicker;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.util.List;

/**
 * The "Assign Barcode" / "Reassign Barcode" pop-up: pick the product (fixed when reassigning),
 * then scan, type or generate the barcode and save it. A barcode scanner types the code and
 * presses Enter, so "Scan" just clears the box and waits for it. The pop-up stays open until
 * the barcode is saved or the user cancels, so a refused barcode can be corrected.
 */
final class AssignBarcodeDialog {

    /** One entry of the product list: shown as "Name (P-0001)". */
    private record Choice(BarcodeRow row) {
        @Override
        public String toString() {
            return row.product().name() + " (" + row.product().productId() + ")";
        }
    }

    private AssignBarcodeDialog() { }

    /**
     * @param withoutBarcode the products offered when {@code fixed} is null
     * @param fixed          the product being reassigned, or null to pick one from the list
     * @return true if a barcode was saved
     */
    static boolean show(Window owner, BarcodeService service, List<BarcodeRow> withoutBarcode, BarcodeRow fixed) {
        return show(owner, service, withoutBarcode, fixed, null);
    }

    /** As above; {@code preselected} (one of {@code withoutBarcode}, or null) is already chosen in the list. */
    static boolean show(Window owner, BarcodeService service, List<BarcodeRow> withoutBarcode, BarcodeRow fixed,
                        BarcodeRow preselected) {
        boolean reassign = fixed != null;
        Modal modal = new Modal(owner, 700);
        boolean[] saved = {false};

        // --- product ---
        SearchPicker<Choice> picker = new SearchPicker<>(false);
        picker.setPromptText("Select Product");
        for (BarcodeRow row : withoutBarcode) {
            Choice choice = new Choice(row);
            picker.getItems().add(choice);
            if (preselected != null && row.product().productId().equals(preselected.product().productId())) {
                picker.setValue(choice);
            }
        }
        picker.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.Node productField = reassign ? BarcodeUi.valueBox(fixed.product().name()) : picker;

        // --- barcode ---
        TextField barcode = BarcodeUi.input("Scan, type or generate a barcode");
        Label hint = new Label();
        hint.setWrapText(true);
        Styles.muted(hint);
        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button scan = BarcodeUi.greyButton("Scan");
        Button generate = BarcodeUi.greyButton("Generate");
        HBox tools = new HBox(24, scan, generate);

        scan.setOnAction(e -> {
            barcode.clear();
            error.setText("");
            hint.setText("Ready: scan the product's barcode now.");
            barcode.requestFocus();
        });
        generate.setOnAction(e -> {
            error.setText("");
            hint.setText("");
            try {
                barcode.setText(service.suggestBarcode());
            } catch (IllegalStateException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        Button cancel = BarcodeUi.greyButton("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button save = BarcodeUi.blueButton("Save Barcode");
        save.setDefaultButton(true);

        // A scanner ends the code with Enter: check it, then wait for the user to press Save.
        barcode.setOnAction(e -> {
            error.setText("");
            hint.setText("");
            String productId = reassign ? fixed.product().productId()
                    : picker.getValue() == null ? null : picker.getValue().row().product().productId();
            String problem = service.problemWith(productId == null ? "" : productId, barcode.getText());
            if (problem != null) {
                error.setText(problem);
            } else {
                hint.setText(productId == null ? "Barcode is fine. Now choose the product."
                        : "Barcode is fine. Press Save Barcode.");
                save.requestFocus();
            }
        });

        save.setOnAction(e -> {
            error.setText("");
            BarcodeRow target = reassign ? fixed : picker.getValue() == null ? null : picker.getValue().row();
            if (target == null) {
                error.setText("Choose a product first.");
                return;
            }
            try {
                String problem = service.problemWith(target.product().productId(), barcode.getText());
                if (problem != null) {
                    error.setText(problem);
                    return;
                }
                if (reassign && !Modal.confirm(save.getScene().getWindow(), "Replace barcode",
                        "Replace the barcode of \"" + target.product().name() + "\"?\n\n"
                                + "Labels already printed with " + target.barcode() + " will no longer be found at the till.",
                        "Replace")) {
                    return;
                }
                service.assign(target.product().productId(), barcode.getText());
                saved[0] = true;
                modal.close();
            } catch (BarcodeAssignException ex) {
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex));
            }
        });

        VBox form = new VBox(16, BarcodeUi.labelled("Product", productField),
                BarcodeUi.labelled("Barcode", barcode), tools, hint, error);
        if (!reassign && withoutBarcode.isEmpty()) {
            hint.setText("Every product already has a barcode. Use Reassign in the table to change one.");
        }
        if (reassign) {
            hint.setText("Current barcode: " + fixed.barcode() + ". Saving replaces it.");
        }

        modal.card().getChildren().addAll(Modal.title(reassign ? "Reassign Barcode" : "Assign Barcode"),
                form, Modal.buttons(cancel, save));
        modal.showAndWait();
        return saved[0];
    }
}
