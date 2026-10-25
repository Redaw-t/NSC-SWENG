package pos.receipt;

import javafx.geometry.VPos;
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
