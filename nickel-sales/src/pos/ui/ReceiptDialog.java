package pos.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.receipt.PrinterPort;
import pos.receipt.Receipt;
import pos.receipt.ReceiptPrintException;
import pos.receipt.ReceiptView;

/**
 * Shows a receipt exactly as it will print, with:
 *   Enter   print on the default printer (then close)
 *   Esc     close without printing
 * A printing problem is shown in the window, which stays open to try again.
 */
final class ReceiptDialog {

    private ReceiptDialog() { }

    /**
     * @param title  window title, e.g. "Sale complete" or "Reprint"
     * @param header bold line at the top
     * @param above  shown above the preview (e.g. the change in big letters); may be null
     * @return true if the receipt was printed
     */
    static boolean show(Window owner, String title, String header, Node above, Receipt receipt,
                        PrinterPort printer, int width) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);

        StackPane paper = new StackPane(ReceiptView.text(receipt.toText(width), 13));
        paper.setPadding(new Insets(16));
        paper.setStyle("-fx-background-color: white; -fx-border-color: #c8c8c8;");
        ScrollPane scroll = new ScrollPane(paper);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(380);
        scroll.setFocusTraversable(false);

        Label error = new Label();
        error.setWrapText(true);
        error.setMaxWidth(480);
        error.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.RED + ";");
        Label keys = new Label("Enter print  ·  Esc close without printing");
        keys.setStyle("-fx-font-size: 12px; -fx-text-fill: #666666;");

        VBox content = new VBox(12);
        if (above != null) {
            content.getChildren().add(above);
        }
        content.getChildren().addAll(scroll, error, keys);
        dialog.getDialogPane().setContent(content);

        ButtonType print = new ButtonType("Print receipt", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(print, close);

        boolean[] printed = {false};
        dialog.getDialogPane().lookupButton(print).addEventFilter(ActionEvent.ACTION, e -> {
            try {
                printer.printReceipt(receipt);
                printed[0] = true;
            } catch (ReceiptPrintException ex) {
                error.setText(ex.getMessage());
                e.consume();   // stay open
            } catch (RuntimeException ex) {
                System.err.println("[PRINT] " + ex);
                error.setText("The receipt could not be printed. Check the printer, then try again.");
                e.consume();
            }
        });
        dialog.setResultConverter(b -> printed[0]);
        Platform.runLater(() -> dialog.getDialogPane().lookupButton(print).requestFocus());
        return dialog.showAndWait().orElse(false);
    }
}
