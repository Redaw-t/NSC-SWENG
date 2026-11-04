package barcode.ui;

import barcode.service.BarcodeRow;
import barcode.service.BarcodeValidator;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The "View" pop-up: the product's barcode, its format and status, and how the label looks. Read only. */
final class BarcodeDetailsDialog {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    private BarcodeDetailsDialog() { }

    static void show(Window owner, BarcodeRow row) {
        Modal modal = new Modal(owner, 700);

        GridPane grid = Modal.grid();
        int r = 0;
        r = Modal.row(grid, r, "Product Code", value(row.product().productId()));
        r = Modal.row(grid, r, "Product Name", value(row.product().name()));
        r = Modal.row(grid, r, "Barcode", value(row.barcode() == null ? "(none)" : row.barcode()));
        if (row.barcode() != null) {
            r = Modal.row(grid, r, "Format", value(formatName(row.barcode())));
        }
        r = Modal.row(grid, r, "Barcode Status", BarcodeUi.pill(row.status(), row.problem()));
        if (row.problem() != null) {
            r = Modal.row(grid, r, "Problem", value(row.problem()));
        }
        r = Modal.row(grid, r, "Last Updated", value(row.lastUpdated() == null ? "-" : WHEN.format(row.lastUpdated())));
        if (row.printable()) {
            Modal.row(grid, r, "Label", BarcodeUi.preview(row.barcode()));
        }

        Button close = BarcodeUi.blueButton("Close");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title("Barcode Details"), grid, Modal.buttons(close));
        modal.showAndWait();
    }

    private static String formatName(String barcode) {
        return switch (BarcodeValidator.formatOf(barcode)) {
            case EAN_13 -> "EAN-13";
            case EAN_8 -> "EAN-8";
            case UPC_A -> "UPC-A";
            case OTHER -> "Other (printed as Code 128)";
        };
    }

    private static Label value(String text) {
        Label label = new Label(text);
        Styles.modalValue(label);
        label.setWrapText(true);
        return label;
    }
}
