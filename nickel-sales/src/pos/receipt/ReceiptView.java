package pos.receipt;

import barcode.ui.BarcodeView;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.List;

/**
 * Draws receipt lines as a block of monospaced text. The screen preview and
 * the printed pages are both made here, from the same lines, so what the
 * cashier sees is what prints.
 */
public final class ReceiptView {

    /** Font family used on screen and paper (every computer has a "Monospaced" font). */
    public static final String FONT = "Monospaced";

    private ReceiptView() { }

    /** The lines as one Text node in the given font size (points). */
    public static Text text(List<String> lines, double fontSize) {
        Text text = new Text(String.join("\n", lines));
        text.setFont(Font.font(FONT, fontSize));
        text.setTextOrigin(VPos.TOP);   // place by the top edge, so nothing is cut off at the top of a page
        return text;
    }

    /**
     * The whole receipt for the preview: text blocks plus the barcode picture,
     * lined up on one paper width. The barcode takes the same share of the paper
     * as it does on the printer.
     *
     * @param width characters per line (the same value used for {@code toParts})
     */
    public static Node node(List<Receipt.Part> parts, double fontSize, int width) {
        double paper = widthOf(width, fontSize);
        VBox box = new VBox();
        box.setAlignment(Pos.TOP_LEFT);
        for (Receipt.Part part : parts) {
            if (part instanceof Receipt.TextBlock block) {
                box.getChildren().add(text(block.lines(), fontSize));
            } else if (part instanceof Receipt.BarcodeBlock code) {
                double scale = paper / ReceiptBarcode.paperDots(width);
                double barsWidth = ReceiptBarcode.barcodeDots(code.value(), width) * scale;
                double barsHeight = ReceiptBarcode.HEIGHT_DOTS * scale;
                StackPane centered = new StackPane(BarcodeView.node(code.value(), barsWidth, barsHeight));
                centered.setAlignment(Pos.CENTER);
                centered.setMinWidth(paper);
                centered.setPrefWidth(paper);
                centered.setMaxWidth(paper);
                box.getChildren().add(centered);
            }
        }
        return box;
    }

    /** Width in points of {@code chars} characters at {@code fontSize}. */
    static double widthOf(int chars, double fontSize) {
        return text(List.of("M".repeat(chars)), fontSize).getLayoutBounds().getWidth();
    }

    /** Height in points of one line at {@code fontSize}. */
    static double lineHeight(double fontSize) {
        double one = text(List.of("M"), fontSize).getLayoutBounds().getHeight();
        double two = text(List.of("M", "M"), fontSize).getLayoutBounds().getHeight();
        return two - one;
    }
}
