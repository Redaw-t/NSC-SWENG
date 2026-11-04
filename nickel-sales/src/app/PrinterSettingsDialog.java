package app;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import shared.Modal;
import shared.PrinterSettings;
import shared.SearchPicker;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.util.List;

/**
 * Settings > Printers: the pop-up for choosing this computer's receipt printer and barcode label
 * printer, each from a searchable list of the installed printers. "Default printer" means
 * NSC_PRINTER_NAME if it is set, else the Windows default (see {@link PrinterSettings}).
 */
final class PrinterSettingsDialog {

    /** A list item: a printer name, or the "use the default" choice (name null). */
    private record Choice(String name, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private PrinterSettingsDialog() { }

    /** @return true if the choices were saved */
    static boolean show(Window owner) {
        Modal modal = new Modal(owner, 700);

        List<String> installed;
        String windowsDefault;
        try {
            installed = PrinterSettings.installed();
            windowsDefault = PrinterSettings.systemDefault();
        } catch (RuntimeException e) {
            Modal.notice(owner, "Printer Settings",
                    UiErrors.message(e, "The printers on this computer could not be listed. Try again."));
            return false;
        }

        String defaultText = windowsDefault == null ? "Default printer (none set in Windows)"
                : "Default printer (" + windowsDefault + ")";
        SearchPicker<Choice> receipt = picker(installed, defaultText, PrinterSettings.chosen(PrinterSettings.Use.RECEIPT));
        SearchPicker<Choice> label = picker(installed, defaultText, PrinterSettings.chosen(PrinterSettings.Use.LABEL));

        GridPane grid = Modal.grid();
        int row = 0;
        row = Modal.row(grid, row, "Receipt Printer", receipt);
        Modal.row(grid, row, "Barcode Label Printer", label);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);
        if (installed.isEmpty()) {
            error.setText("No printers are installed on this computer. Add one in Windows Settings > Printers.");
        }

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());

        boolean[] saved = {false};
        Button save = new Button("Save");
        Styles.modalButton(save, Theme.INFORMATION, "white");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            try {
                PrinterSettings.save(PrinterSettings.Use.RECEIPT, nameOf(receipt));
                PrinterSettings.save(PrinterSettings.Use.LABEL, nameOf(label));
                saved[0] = true;
                modal.close();
            } catch (RuntimeException ex) {
                error.setText(UiErrors.message(ex, "The printer settings could not be saved. Try again."));
            }
        });

        modal.card().getChildren().addAll(Modal.title("Printer Settings"),
                Modal.subtitle("Choose which printer this computer uses. Receipts and labels must go to a "
                        + "thermal (ESC/POS) printer."),
                grid, error, Modal.buttons(cancel, save));
        modal.showAndWait();
        return saved[0];
    }

    /** A searchable list: "Default printer (...)" first, then every installed printer. */
    private static SearchPicker<Choice> picker(List<String> installed, String defaultText, String current) {
        SearchPicker<Choice> picker = new SearchPicker<>(true);
        Choice useDefault = new Choice(null, defaultText);
        picker.getItems().add(useDefault);
        Choice selected = useDefault;
        for (String name : installed) {
            Choice c = new Choice(name, name);
            picker.getItems().add(c);
            if (name.equalsIgnoreCase(current)) {
                selected = c;
            }
        }
        if (current != null && selected == useDefault) {
            // The saved printer was removed from this computer: keep it visible so the user sees why.
            Choice missing = new Choice(current, current + "  (not found)");
            picker.getItems().add(1, missing);
            selected = missing;
        }
        picker.setValue(selected);
        return picker;
    }

    private static String nameOf(SearchPicker<Choice> picker) {
        return picker.getValue() == null ? null : picker.getValue().name();
    }
}
