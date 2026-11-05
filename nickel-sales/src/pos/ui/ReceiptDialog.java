package pos.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
 * Shows a receipt exactly as it will print, drawn with the shared {@link Modal}. The buttons show
 * their keys underneath like the payment card: Print Receipt [ENTER], Close [ESC] (no printing).
 * A printing problem is shown in the pop-up, which stays open to try again.
 */
final class ReceiptDialog {

    private static final double PAPER_PADDING = 4;      // a little white around the picture (it has its own margins)
    private static final double TRAY_PADDING = 12;      // grey margin around the paper
    private static final double SCROLL_BAR = 12;        // room for the slim scroll bar
    private static final double CARD_SIDE_PADDING = 48; // shared.Modal's card padding, left and right
    private static final double MIN_CARD_WIDTH = 520;   // enough for the title and the two buttons

    private ReceiptDialog() { }

    /**
     * @param title    the big title, e.g. "Sale Complete" or "Reprint Receipt"
     * @param subtitle the grey line under it, e.g. "Invoice T01-2026-000123"
     * @param above    shown above the preview (e.g. the change in big letters); may be null
     * @return true if the receipt was printed
     */
    static boolean show(Window owner, String title, String subtitle, Node above, Receipt receipt,
                        PrinterPort printer, int width) {
        // Size everything from the receipt's real width, so there is no empty white space beside it.
        double receiptWidth = ReceiptView.paperWidth(width);
        double paperWidth = receiptWidth + 2 * PAPER_PADDING;
        double viewWidth = paperWidth + 2 * TRAY_PADDING + SCROLL_BAR;
        Modal modal = new Modal(owner, Math.max(MIN_CARD_WIDTH, viewWidth + 2 * CARD_SIDE_PADDING));
        boolean[] printed = {false};

        StackPane paper = new StackPane(ReceiptView.node(receipt, width));   // the same picture that prints
        paper.setPadding(new Insets(PAPER_PADDING));
        paper.setMinWidth(paperWidth);
        paper.setPrefWidth(paperWidth);
        paper.setMaxWidth(paperWidth);
        paper.setStyle("-fx-background-color: white; -fx-border-color: " + Theme.BORDER + ";");
        paper.setEffect(new DropShadow(10, 0, 2, Color.rgb(0, 0, 0, 0.10)));
        StackPane tray = new StackPane(paper);   // the grey area the paper sits on
        tray.setAlignment(Pos.TOP_CENTER);
        tray.setPadding(new Insets(TRAY_PADDING));

        ScrollPane scroll = new ScrollPane(tray);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(380);
        scroll.setFocusTraversable(false);
        Styles.slimScrollBar(scroll);
        StackPane frame = new StackPane(scroll);
        frame.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + "; -fx-background-radius: 8; "
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-radius: 8;");

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);

        Button close = PosStyle.modalKeyButton("Close", "ESC", PosStyle.Kind.SECONDARY);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());
        Button print = PosStyle.modalKeyButton("Print Receipt", "ENTER", PosStyle.Kind.PRIMARY);
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
        modal.card().getChildren().addAll(frame, error, Modal.buttons(close, print));
        Platform.runLater(print::requestFocus);
        modal.showAndWait();
        return printed[0];
    }
}
