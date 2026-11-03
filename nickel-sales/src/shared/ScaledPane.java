package shared;

import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

/**
 * Lays one screen out at the width it was DESIGNED for (the mockups are 1920 px
 * wide) and scales it to fit the real window, so the screen looks the same on a
 * 1280 px laptop and a 1920 px monitor.
 *
 * The scale follows the window WIDTH. The content is given the matching extra
 * height (a taller window shows more rows instead of empty bars), so any aspect
 * ratio fills the window. The scale is kept between half and double size.
 */
public final class ScaledPane extends Pane {

    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 2.0;

    private final Region content;
    private final double designWidth;
    private final Scale scale = new Scale(1, 1, 0, 0);

    /**
     * @param content     the screen, drawn in design pixels
     * @param designWidth the width of the mockup in pixels, e.g. 1920
     */
    public ScaledPane(Region content, double designWidth) {
        this.content = content;
        this.designWidth = designWidth;
        content.getTransforms().add(scale);
        getChildren().add(content);
        setMinSize(0, 0);
    }

    /** The current scale (window width divided by design width, kept between 0.5 and 2). */
    public double getScale() {
        return scale.getX();
    }

    @Override
    protected void layoutChildren() {
        double s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, getWidth() / designWidth));
        scale.setX(s);
        scale.setY(s);
        content.resizeRelocate(0, 0, getWidth() / s, getHeight() / s);
    }
}
