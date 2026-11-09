package shared;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * The top of a screen with tabs, the same on every screen: the tab strip, and under it on the right the
 * "↻ Refresh" button (as on the dashboard), which loads the shown tab again from the database. The
 * tab's filters stay as they are.
 */
public final class PageHeader {

    private PageHeader() { }

    /** {@code tabs} with the Refresh button under it on the right; {@code refresh} reloads the shown tab. */
    public static VBox of(Node tabs, Runnable refresh) {
        Objects.requireNonNull(refresh, "refresh");
        HBox row = new HBox(refreshButton(refresh));
        row.setAlignment(Pos.CENTER_RIGHT);
        VBox header = new VBox(6, tabs, row);
        header.setMinHeight(Region.USE_PREF_SIZE);
        return header;
    }

    /** The dashboard's "↻ Refresh" button. */
    public static Button refreshButton(Runnable refresh) {
        Button button = new Button("↻  Refresh");
        Styles.rowButton(button, Theme.BORDER, Theme.PRIMARY_TEXT);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip("Load this page again (your filters stay)"));
        button.setOnAction(e -> refresh.run());
        return button;
    }
}
