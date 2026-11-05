package pos.receipt;

import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.awt.image.BufferedImage;

/**
 * The on-screen receipt preview: the very picture {@link ReceiptRenderer} makes for the printer,
 * shown dot for dot, so what the cashier sees is what prints.
 */
public final class ReceiptView {

    private ReceiptView() { }

    /** The receipt picture as a JavaFX image, one screen pixel per printer dot. */
    public static ImageView node(Receipt receipt, int widthChars) {
        return node(ReceiptRenderer.render(receipt, widthChars));
    }

    /** A rendered receipt picture as a JavaFX image, one screen pixel per printer dot. */
    public static ImageView node(BufferedImage picture) {
        WritableImage image = new WritableImage(picture.getWidth(), picture.getHeight());
        PixelWriter px = image.getPixelWriter();
        for (int y = 0; y < picture.getHeight(); y++) {
            for (int x = 0; x < picture.getWidth(); x++) {
                px.setColor(x, y, (picture.getRGB(x, y) & 0xFFFFFF) == 0 ? Color.BLACK : Color.WHITE);
            }
        }
        ImageView view = new ImageView(image);
        view.setSmooth(false);   // keep the dots sharp, as on the paper
        return view;
    }

    /** Width on screen of a receipt {@code widthChars} characters wide (for sizing the preview). */
    public static double paperWidth(int widthChars) {
        return ReceiptRenderer.dotsFor(widthChars);
    }
}
