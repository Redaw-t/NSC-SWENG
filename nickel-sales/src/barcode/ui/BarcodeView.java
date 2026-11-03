package barcode.ui;

import barcode.service.Code128;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** Draws a Code 128 barcode on screen (the receipt preview). */
public final class BarcodeView {

    private BarcodeView() { }

    /**
     * The barcode for {@code value}, {@code width} x {@code height} pixels, black on
     * a transparent background (put it on white). Bar edges are snapped to whole
     * pixels so the bars stay sharp at any width.
     */
    public static Canvas node(String value, double width, double height) {
        boolean[] modules = Code128.modules(value);
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
