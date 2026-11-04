package pos.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import pos.receipt.PrinterPort;
import pos.receipt.Receipt;
import pos.receipt.ReceiptPrintException;
import pos.receipt.ReceiptView;
import shared.Modal;
import shared.Styles;
import shared.Theme;

/**
 * Shows a receipt exactly as it will print, drawn with the shared {@link Modal}:
 *   Enter   print (then close)
 *   Esc     close without printing
 * A printing problem is shown in the pop-up, which stays open to try again.
 */
final class ReceiptDialog {

    private ReceiptDialog() { }

    /**
     * @param title    the big title, e.g. "Sale Complete" or "Reprint Receipt"
     * @param subtitle the grey line under it, e.g. "Invoice T01-2026-000123"
     * @param above    shown above the preview (e.g. the change in big letters); may be null
     * @return true if the receipt was printed
     */
    static boolean show(Window owner, String title, String subtitle, Node above, Receipt receipt,
                        PrinterPort printer, int width) {
        Modal modal = new Modal(owner, 640);
        boolean[] printed = {false};

        StackPane paper = new StackPane(ReceiptView.node(receipt.toParts(width), 13, width));
        paper.setPadding(new Insets(18));
        paper.setStyle("-fx-background-color: white; -fx-border-color: " + Theme.BORDER + ";");
        paper.setEffect(new DropShadow(10, 0, 2, Color.rgb(0, 0, 0, 0.10)));
        StackPane holder = new StackPane(paper);
        holder.setPadding(new Insets(8));
        holder.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + ";");
        ScrollPane scroll = new ScrollPane(holder);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(360);
        scroll.setFocusTraversable(false);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: " + Theme.APP_BACKGROUND + "; "
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-radius: 4;");

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);
        Label keys = new Label("Enter print   ·   Esc close without printing");
        Styles.muted(keys);

        Button close = new Button("Close");
        Styles.modalButton(close, Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());
        Button print = new Button("Print Receipt");
        Styles.modalButton(print, Theme.INFORMATION, "white");
        print.setDefaultButton(true);
        print.setOnAction(e -> {
            try {
                printer.printReceipt(receipt);
                printed[0] = true;
                modal.close();
            } catch (ReceiptPrintException ex) {
                error.setText(ex.getMessage());   // stay open
            } catch (RuntimeException ex) {
                System.err.println("[PRINT] " + ex);
                error.setText("The receipt could not be printed. Check the printer, then try again.");
            }
        });

        modal.card().getChildren().addAll(Modal.title(title), Modal.subtitle(subtitle));
        if (above != null) {
            modal.card().getChildren().add(above);
        }
        modal.card().getChildren().addAll(scroll, error, keys, Modal.buttons(close, print));
        Platform.runLater(print::requestFocus);
        modal.showAndWait();
        return printed[0];
    }
}
