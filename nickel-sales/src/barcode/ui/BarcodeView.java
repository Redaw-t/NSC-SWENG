package barcode.ui;

import barcode.service.Code128;
import barcode.service.Ean;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** Draws a barcode on screen (the receipt preview and the label preview). */
public final class BarcodeView {

    private BarcodeView() { }

    /**
     * The Code 128 barcode for {@code value}, {@code width} x {@code height} pixels, black on
     * a transparent background (put it on white). Bar edges are snapped to whole
     * pixels so the bars stay sharp at any width.
     */
    public static Canvas node(String value, double width, double height) {
        return draw(Code128.modules(value), width, height);
    }

    /**
     * The barcode as a product LABEL prints it: EAN-13, UPC-A and EAN-8 codes with a correct check
     * digit are drawn as EAN / UPC bars (what the label printer's own command prints); any other
     * code is drawn as Code 128. Same size and colours as {@link #node}.
     */
    public static Canvas label(String value, double width, double height) {
        return draw(Ean.canDraw(value) ? Ean.modules(value) : Code128.modules(value), width, height);
    }

    private static Canvas draw(boolean[] modules, double width, double height) {
        Canvas canvas = new Canvas(Math.ceil(width), Math.ceil(height));
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.BLACK);
        double scale = canvas.getWidth() / modules.length;
        int i = 0;
        while (i < modules.length) {
            if (!modules[i]) {
                i++;
                continue;
            }
            int start = i;
            while (i < modules.length && modules[i]) {
                i++;
            }
            double x0 = Math.round(start * scale);
            double x1 = Math.round(i * scale);
            g.fillRect(x0, 0, Math.max(1, x1 - x0), canvas.getHeight());
        }
        return canvas;
    }
}
