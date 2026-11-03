package pos.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.Region;
import shared.Theme;

import java.util.function.Predicate;

/**
 * The look of the POS screen, in Java (this replaced resources/css/pos.css).
 *
 * Sizes are "design pixels": the screen is drawn 1920 px wide and scaled to the window
 * (see shared.ScaledPane). Colours come from shared.Theme; the hover / pressed shades of the
 * three button kinds are the few that are not in the palette.
 *
 * Inline styles cannot say ":hover", so the buttons repaint themselves from listeners.
 */
final class PosStyle {

    /** The three button colours: normal, hovered and pressed. */
    enum Kind {
        SECONDARY(Theme.BORDER, "#CBD2DE", "#BDC5D3"),
        PRIMARY(Theme.INFORMATION, "#2F70AE", "#265F95"),
        DANGER(Theme.ACCENT_RED, "#D44848", "#C03B3B");

        private final String normal;
        private final String hover;
        private final String pressed;

        Kind(String normal, String hover, String pressed) {
            this.normal = normal;
            this.hover = hover;
            this.pressed = pressed;
        }
    }

    private static final String KIND = "pos.style.kind";
    private static final String PLAIN_EXTRA = "pos.style.plain";
    private static final String LISTENING = "pos.style.listening";

    private static final String GRID_LINE = "#D9DDE5";
    private static final String SELECTED_ROW = Theme.BORDER;
    private static final String PROBLEM_ROW = "#fbe3e3";

    private static final String BUTTON_BASE = "-fx-background-radius: 10; -fx-padding: 0; -fx-cursor: hand; "
            + "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; ";

    private PosStyle() { }

    /** The whole POS screen: app background and the font for everything inside. */
    static void root(Region content) {
        content.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + "; -fx-font-family: \"" + Theme.FONT_FAMILY + "\";");
    }

    /* -------------------------------- buttons -------------------------------- */

    /** Colours a button; call again to change its colour later (e.g. the chosen payment method). */
    static void button(Button button, Kind kind) {
        button.getProperties().remove(PLAIN_EXTRA);
        button.getProperties().put(KIND, kind);
        listen(button);
        paint(button);
    }

    /** A see-through button that gets a faint tint under the mouse (menu, Logout). {@code extra} is appended to its style. */
    static void plainButton(Button button, String extra) {
        button.getProperties().remove(KIND);
        button.getProperties().put(PLAIN_EXTRA, extra == null ? "" : extra);
        listen(button);
        paint(button);
    }

    private static void listen(Button button) {
        if (button.getProperties().putIfAbsent(LISTENING, Boolean.TRUE) == null) {
            button.hoverProperty().addListener((obs, was, is) -> paint(button));
            button.pressedProperty().addListener((obs, was, is) -> paint(button));
        }
    }

    private static void paint(Button button) {
        Object kind = button.getProperties().get(KIND);
        if (kind instanceof Kind k) {
            String colour = button.isPressed() ? k.pressed : button.isHover() ? k.hover : k.normal;
            button.setStyle(BUTTON_BASE + "-fx-background-color: " + colour + ";");
            return;
        }
        Object extra = button.getProperties().get(PLAIN_EXTRA);
        String tint = button.isHover() ? "rgba(31, 41, 55, 0.08)" : "transparent";
        button.setStyle(BUTTON_BASE + "-fx-background-color: " + tint + "; " + (extra == null ? "" : extra));
    }

    /* --------------------------------- table --------------------------------- */

    /** The cart table: blue 78 px header, 76 px rows, grid lines. Call before it is shown. */
    static void table(TableView<?> table) {
        table.setFixedCellSize(76);
        table.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + "; -fx-background-insets: 0; -fx-padding: 0; "
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-width: 2; -fx-table-cell-border-color: " + GRID_LINE
                + "; -fx-font-size: 22px;");
        // The header parts only exist once the table has its skin, so they are styled then.
        table.skinProperty().addListener((obs, old, skin) -> Platform.runLater(() -> styleHeader(table)));
    }

    /** The text shown when the cart is empty. */
    static void placeholder(Label label) {
        label.setStyle("-fx-text-fill: " + Theme.SECONDARY_TEXT + "; -fx-font-size: 22px;");
    }

    private static void styleHeader(TableView<?> table) {
        String blue = "-fx-background-color: " + Theme.PRIMARY_BLUE + ";";
        for (Node n : table.lookupAll(".column-header-background")) {
            n.setStyle(blue + " -fx-background-insets: 0; -fx-padding: 0;");
        }
        for (Node n : table.lookupAll(".filler")) {
            n.setStyle(blue + " -fx-border-width: 0;");
        }
        for (Node n : table.lookupAll(".column-resize-line")) {
            n.setStyle("-fx-background-color: transparent;");
        }
        for (Node header : table.lookupAll(".column-header")) {
            header.setStyle(blue + " -fx-background-insets: 0; -fx-size: 78px; -fx-border-color: transparent; -fx-padding: 0;");
            for (Node label : header.lookupAll(".label")) {
                label.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 22px; -fx-alignment: CENTER;");
            }
        }
    }

    /** A cart row: plain, light grey when selected, light red when {@code problem} says the line has a stock problem. */
    static final class CartRow<S> extends TableRow<S> {

        private final Predicate<S> problem;

        CartRow(Predicate<S> problem) {
            this.problem = problem;
            selectedProperty().addListener((obs, was, is) -> paint());
            paint();
        }

        @Override
        protected void updateItem(S item, boolean empty) {
            super.updateItem(item, empty);
            paint();
        }

        private void paint() {
            boolean filled = !isEmpty() && getItem() != null;
            String background = filled && problem.test(getItem()) ? PROBLEM_ROW
                    : filled && isSelected() ? SELECTED_ROW : Theme.APP_BACKGROUND;
            setStyle("-fx-background-color: " + background + "; -fx-background-insets: 0; -fx-padding: 0; -fx-border-width: 0;");
        }
    }

    /** A centred cart cell with the grid lines (also drawn in the empty rows). */
    static final class GridCell<S> extends TableCell<S, String> {

        GridCell() {
            setStyle("-fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-border-color: " + GRID_LINE
                    + "; -fx-border-width: 0 1 1 0; -fx-padding: 0 8 0 8; -fx-alignment: CENTER;");
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item);
        }
    }
}
