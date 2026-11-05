package shared;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Inline JavaFX styles for the sidebar frame, the Products screens and their pop-ups, built from the
 * {@link Theme} colours so a colour change is made in one place. Every method styles
 * one kind of control; hover and focus effects are done with listeners because an inline
 * style cannot say ":hover".
 */
public final class Styles {

    private static final String NORMAL = "nsc.style.normal";
    private static final String HOVER = "nsc.style.hover";
    private static final String HOVER_INSTALLED = "nsc.style.hover.installed";

    private Styles() { }

    /* ------------------------------ colour helpers ------------------------------ */

    /** {@code hex} with an alpha, as a CSS rgba(...) value. */
    public static String rgba(String hex, double alpha) {
        Color c = Color.web(hex);
        return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.2f)",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255), alpha);
    }

    /** A slightly darker shade of {@code hex}, for hover. */
    public static String darker(String hex) {
        Color c = Color.web(hex).deriveColor(0, 1, 0.88, 1);
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }

    private static String font() {
        return "-fx-font-family: \"" + Theme.FONT_FAMILY + "\";";
    }

    /** Sets a style and swaps it for {@code hover} while the mouse is over the node (one listener, however often called). */
    private static void withHover(Node node, String normal, String hover) {
        node.getProperties().put(NORMAL, normal);
        node.getProperties().put(HOVER, hover);
        node.setStyle(node.isHover() ? hover : normal);
        if (node.getProperties().putIfAbsent(HOVER_INSTALLED, Boolean.TRUE) == null) {
            node.hoverProperty().addListener((obs, was, is) ->
                    node.setStyle((String) node.getProperties().get(is ? HOVER : NORMAL)));
        }
    }

    /* --------------------------------- sidebar --------------------------------- */

    /** The window's root: app background and the font for everything inside. */
    public static void shellRoot(Region root) {
        root.setStyle(font() + " -fx-font-size: 14px; -fx-background-color: " + Theme.APP_BACKGROUND + ";");
    }

    public static void sidebar(Region sidebar) {
        sidebar.setStyle("-fx-background-color: " + Theme.BORDER + ";");
    }

    /** The lighter strip at the top of the sidebar (logo, name, hamburger). */
    public static void sidebarHeader(Region header) {
        header.setStyle("-fx-background-color: " + rgba(Theme.SURFACE, 0.6) + ";");
    }

    public static void brandName(Label label) {
        label.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
    }

    public static void content(Region content) {
        content.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + ";");
    }

    public static void burger(Button button) {
        button.setStyle("-fx-background-color: transparent; -fx-padding: 8; -fx-cursor: hand;");
    }

    public static void burgerLine(Region line) {
        line.setStyle("-fx-background-color: " + Theme.SECONDARY_TEXT + ";");
        line.setMinSize(24, 2);
        line.setPrefSize(24, 2);
        line.setMaxSize(24, 2);
    }

    public static void navItem(Button button, boolean active) {
        String base = "-fx-background-radius: 0; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-font-size: 13px; "
                + "-fx-font-weight: bold; -fx-alignment: CENTER_LEFT; -fx-padding: 0 0 0 18; "
                + "-fx-pref-height: 44; -fx-min-height: 44; -fx-cursor: hand; -fx-background-color: ";
        String normal = base + (active ? rgba(Theme.PRIMARY_BLUE, 0.20) : "transparent") + ";";
        String hover = base + rgba(Theme.PRIMARY_BLUE, active ? 0.20 : 0.08) + ";";
        withHover(button, normal, hover);
    }

    public static void userName(Label label) {
        label.setStyle("-fx-text-fill: " + Theme.SECONDARY_TEXT + "; -fx-font-size: 11px;");
    }

    public static void logout(Button button) {
        String base = "-fx-background-radius: 999; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; "
                + "-fx-pref-height: 28; -fx-cursor: hand; -fx-background-color: ";
        withHover(button, base + Theme.PRIMARY_BLUE + ";", base + Theme.SECONDARY_BLUE + ";");
    }

    /* ----------------------------- tab strip, inputs ---------------------------- */

    public static void tabBar(Region bar) {
        bar.setStyle("-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 4;");
    }

    public static void tab(Button button, boolean active) {
        String base = "-fx-background-radius: 4; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-font-size: 16px; "
                + "-fx-pref-height: 46; -fx-cursor: hand; -fx-font-weight: " + (active ? "bold" : "normal")
                + "; -fx-background-color: ";
        withHover(button, base + "transparent;", base + (active ? "transparent;" : rgba(Theme.PRIMARY_BLUE, 0.08) + ";"));
    }

    public static void tabDivider(Label label) {
        label.setStyle("-fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-font-size: 18px;");
    }

    private static String inputStyle(String borderColour) {
        return "-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 4; -fx-border-color: "
                + borderColour + "; -fx-border-radius: 4; -fx-pref-height: 40; -fx-font-size: 15px; "
                + "-fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-prompt-text-fill: " + Theme.SECONDARY_TEXT + ";";
    }

    /** The grey search box. */
    public static void input(TextField field) {
        String normal = inputStyle("transparent") + " -fx-padding: 0 12 0 12;";
        String focused = inputStyle(Theme.PRIMARY_BLUE) + " -fx-padding: 0 12 0 12;";
        field.setStyle(normal);
        field.focusedProperty().addListener((obs, was, is) -> field.setStyle(is ? focused : normal));
    }

    /** The grey filter drop-down. The popup list keeps JavaFX's default look. */
    public static void input(ComboBox<?> combo) {
        String normal = inputStyle("transparent");
        String focused = inputStyle(Theme.PRIMARY_BLUE);
        combo.setStyle(normal);
        combo.focusedProperty().addListener((obs, was, is) -> combo.setStyle(is ? focused : normal));
    }

    /* --------------------------------- buttons --------------------------------- */

    public static void primaryButton(Button button) {
        String base = "-fx-background-radius: 4; -fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold; "
                + "-fx-padding: 0 22 0 22; -fx-pref-height: 40; -fx-cursor: hand; -fx-background-color: ";
        withHover(button, base + Theme.PRIMARY_BLUE + ";", base + Theme.SECONDARY_BLUE + ";");
    }

    /** The small rounded button inside a table row. */
    public static void rowButton(Button button, String background, String textColour) {
        String base = "-fx-background-radius: 999; -fx-font-size: 12px; -fx-padding: 5 14 5 14; -fx-cursor: hand; "
                + "-fx-text-fill: " + textColour + "; -fx-background-color: ";
        withHover(button, base + background + ";", base + darker(background) + ";");
    }

    public static void pagerLink(Button button, boolean current) {
        String base = "-fx-background-color: transparent; -fx-padding: 2 5 2 5; -fx-cursor: hand; "
                + (current ? "-fx-font-weight: bold; " : "") + "-fx-text-fill: ";
        withHover(button, base + (current ? Theme.PRIMARY_BLUE : Theme.SECONDARY_TEXT) + ";",
                base + Theme.PRIMARY_BLUE + ";");
    }

    /* ---------------------------- labels, pills, cards --------------------------- */

    public static void pill(Label label, String background, String textColour) {
        label.setStyle("-fx-background-color: " + background + "; -fx-background-radius: 999; "
                + "-fx-padding: 4 14 4 14; -fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + textColour + ";");
    }

    public static void muted(Label label) {
        label.setStyle("-fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
    }

    public static void error(Label label) {
        label.setStyle("-fx-text-fill: " + Theme.ERROR + ";");
    }

    public static void card(Region card) {
        card.setStyle("-fx-background-color: " + Theme.SURFACE + "; -fx-background-radius: 8; "
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-radius: 8;");
    }

    public static void cardTitle(Label label) {
        label.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_BLUE + ";");
    }

    public static void cardText(Label label) {
        label.setStyle("-fx-font-size: 16px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
    }

    public static void detailKey(Label label) {
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
    }

    public static void detailValue(Label label) {
        label.setStyle("-fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
    }

    /* ---------------------------------- table ---------------------------------- */

    /** The style of one table cell; {@code empty} cells (filler rows) keep only the vertical grid line. */
    public static String cellStyle(boolean empty) {
        return "-fx-alignment: CENTER; -fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; "
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-width: " + (empty ? "0 1 0 0" : "0 1 1 0") + ";";
    }

    /** Blue header, grid lines, hover / selected row tint. Call once per table, before showing it. */
    public static <S> void table(TableView<S> table) {
        table.setStyle("-fx-background-color: " + Theme.SURFACE + "; -fx-background-insets: 0; -fx-padding: 0; "
                + "-fx-border-color: " + Theme.SECONDARY_BLUE + "; -fx-border-width: 1;");
        table.setRowFactory(tv -> new TintedRow<S>());
        // The header parts only exist once the table has its skin, so they are styled then.
        table.skinProperty().addListener((obs, old, skin) -> Platform.runLater(() -> styleHeader(table)));
    }

    private static void styleHeader(TableView<?> table) {
        for (Node n : table.lookupAll(".column-header-background")) {
            n.setStyle("-fx-background-color: " + Theme.PRIMARY_BLUE + ";");
        }
        for (Node n : table.lookupAll(".filler")) {
            n.setStyle("-fx-background-color: " + Theme.PRIMARY_BLUE + ";");
        }
        for (Node header : table.lookupAll(".column-header")) {
            header.setStyle("-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-border-color: "
                    + Theme.SECONDARY_BLUE + "; -fx-border-width: 0 1 0 0; -fx-size: 52;");
            for (Node label : header.lookupAll(".label")) {
                label.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold; "
                        + "-fx-alignment: CENTER; -fx-text-alignment: CENTER;");
            }
        }
    }

    /** A row that turns light blue under the mouse or when selected, and is plain white when empty. */
    private static final class TintedRow<S> extends TableRow<S> {

        TintedRow() {
            hoverProperty().addListener((obs, was, is) -> paint());
            selectedProperty().addListener((obs, was, is) -> paint());
            paint();
        }

        @Override
        protected void updateItem(S item, boolean empty) {
            super.updateItem(item, empty);
            paint();
        }

        private void paint() {
            boolean tint = !isEmpty() && (isHover() || isSelected());
            setStyle("-fx-background-color: " + (tint ? rgba(Theme.PRIMARY_BLUE, 0.15) : Theme.SURFACE)
                    + "; -fx-table-cell-border-color: transparent;");
        }
    }

    /** A centred, grid-lined cell for text columns. */
    public static final class GridCell<S> extends TableCell<S, String> {

        public GridCell() {
            setStyle(cellStyle(true));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item);
            setStyle(cellStyle(empty));
        }
    }

    /* ------------------------------ searchable picker ------------------------------ */

    /** The box of a SearchPicker: a grey filter box, or a form line when {@code underline}. */
    public static void picker(Region box, boolean underline) {
        String normal = pickerBox(underline, underline ? Theme.SECONDARY_TEXT : "transparent");
        String focused = pickerBox(underline, Theme.PRIMARY_BLUE);
        box.setStyle(normal);
        box.focusedProperty().addListener((obs, was, is) -> box.setStyle(is ? focused : normal));
    }

    private static String pickerBox(boolean underline, String line) {
        if (underline) {
            return "-fx-background-color: transparent; -fx-border-color: transparent transparent " + line
                    + " transparent; -fx-border-width: 0 0 1.5 0; -fx-pref-height: 40; -fx-min-height: 40; "
                    + "-fx-padding: 0 4 0 2; -fx-cursor: hand;";
        }
        return "-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 4; -fx-border-color: " + line
                + "; -fx-border-radius: 4; -fx-pref-height: 40; -fx-min-height: 40; -fx-padding: 0 10 0 12; "
                + "-fx-cursor: hand;";
    }

    /** The chosen name (or the grey prompt) inside a SearchPicker. */
    public static void pickerText(Label label, boolean underline, boolean prompt) {
        label.setStyle("-fx-font-size: " + (underline ? 17 : 15) + "px; -fx-text-fill: "
                + (prompt ? Theme.SECONDARY_TEXT : Theme.PRIMARY_TEXT) + ";");
    }

    public static void pickerArrow(Label label) {
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-padding: 0 0 0 8;");
    }

    /** The panel that drops down: search box and list. */
    public static void pickerPopup(Region panel) {
        panel.setStyle("-fx-background-color: " + Theme.SURFACE + "; -fx-background-radius: 10; -fx-border-color: "
                + Theme.BORDER + "; -fx-border-radius: 10; " + font() + " -fx-font-size: 14px;");
    }

    /** One row of a picker list; {@code tint} 0 = plain, otherwise how strong the soft blue is (0.08 hover, 0.14 selected). */
    public static void pickerCell(Region cell, double tint) {
        cell.setStyle("-fx-background-color: " + (tint > 0 ? rgba(Theme.PRIMARY_BLUE, tint) : "transparent")
                + "; -fx-background-radius: 6; -fx-padding: 0 12 0 12; -fx-font-size: 15px; "
                + "-fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
    }

    public static void pickerList(ListView<?> list) {
        list.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; "
                + "-fx-font-size: 15px;");
    }

    /* --------------------------------- modals --------------------------------- */

    /** The white rounded card of a pop-up, with a soft shadow. */
    public static void modalCard(Region card) {
        card.setStyle("-fx-background-color: " + Theme.SURFACE + "; -fx-background-radius: 20; "
                + font() + " -fx-font-size: 14px;");
        card.setEffect(new DropShadow(28, 0, 8, Color.rgb(0, 0, 0, 0.30)));
    }

    public static void modalTitle(Label label) {
        label.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
    }

    public static void modalSubtitle(Label label) {
        label.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
    }

    /** The text on the left of a form row. */
    public static void modalLabel(Label label) {
        label.setStyle("-fx-font-size: 17px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
    }

    /** A value shown (not edited) on the right of a row. */
    public static void modalValue(Label label) {
        label.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
    }

    public static void modalCheckBox(CheckBox box) {
        box.setStyle("-fx-font-size: 16px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
    }

    private static String underlineStyle(String line) {
        return underlineStyle(line, Theme.SECONDARY_TEXT);
    }

    private static String underlineStyle(String line, String promptColour) {
        return "-fx-background-color: transparent; -fx-background-radius: 0; -fx-border-color: transparent transparent "
                + line + " transparent; -fx-border-width: 0 0 1.5 0; -fx-font-size: 17px; -fx-pref-height: 40; "
                + "-fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-prompt-text-fill: " + promptColour + ";";
    }

    /** A form field drawn as a line (the underline turns blue while typing). */
    public static void underline(TextField field) {
        String normal = underlineStyle(Theme.SECONDARY_TEXT) + " -fx-padding: 6 2 4 2;";
        String focused = underlineStyle(Theme.PRIMARY_BLUE) + " -fx-padding: 6 2 4 2;";
        field.setStyle(normal);
        field.focusedProperty().addListener((obs, was, is) -> field.setStyle(is ? focused : normal));
    }

    /** Like {@link #underline(TextField)} but the hint text is faint (for an optional "Others" field). */
    public static void underlineFaintPrompt(TextField field) {
        String faint = rgba(Theme.SECONDARY_TEXT, 0.55);
        String normal = underlineStyle(Theme.SECONDARY_TEXT, faint) + " -fx-padding: 6 2 4 2;";
        String focused = underlineStyle(Theme.PRIMARY_BLUE, faint) + " -fx-padding: 6 2 4 2;";
        field.setStyle(normal);
        field.focusedProperty().addListener((obs, was, is) -> field.setStyle(is ? focused : normal));
    }

    /** A field the user can see but not change (the product ID). */
    public static void underlineReadOnly(TextField field) {
        field.setEditable(false);
        field.setFocusTraversable(false);
        field.setStyle(underlineStyle(Theme.BORDER) + " -fx-padding: 6 2 4 2; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
    }

    public static void underline(ComboBox<?> combo) {
        String normal = underlineStyle(Theme.SECONDARY_TEXT);
        String focused = underlineStyle(Theme.PRIMARY_BLUE);
        combo.setStyle(normal);
        combo.focusedProperty().addListener((obs, was, is) -> combo.setStyle(is ? focused : normal));
    }

    /** The big rounded buttons at the bottom of a pop-up (Cancel, Save, ...). */
    public static void modalButton(Button button, String background, String textColour) {
        String base = "-fx-background-radius: 8; -fx-font-size: 18px; -fx-text-fill: " + textColour
                + "; -fx-min-width: 150; -fx-pref-height: 52; -fx-cursor: hand; -fx-background-color: ";
        withHover(button, base + background + ";", base + darker(background) + ";");
    }

    /** The small grey "Generate" button. */
    public static void smallPillButton(Button button) {
        String base = "-fx-background-radius: 999; -fx-font-size: 13px; -fx-padding: 4 16 4 16; -fx-cursor: hand; "
                + "-fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-background-color: ";
        withHover(button, base + Theme.BORDER + ";", base + darker(Theme.BORDER) + ";");
    }

    /* ------------------------------ slim scroll bar ------------------------------ */

    /**
     * The slim, rounded scroll bar of the Dashboard: no arrows, no track, a soft blue thumb that
     * widens and darkens a little under the mouse. Also removes the ScrollPane's own grey frame.
     */
    public static void slimScrollBar(ScrollPane scroll) {
        scroll.getStyleClass().add("slim-scroll");
        scroll.getStylesheets().add(SLIM_SCROLL_CSS);
    }

    private static final String SLIM_SCROLL_CSS = "data:text/css;base64," + Base64.getEncoder().encodeToString(
            String.join("\n",
                    ".slim-scroll, .slim-scroll:focused { -fx-background-color: transparent; -fx-background-insets: 0; "
                            + "-fx-padding: 0; -fx-border-color: transparent; }",
                    ".slim-scroll > .viewport { -fx-background-color: transparent; }",
                    ".slim-scroll > .corner { -fx-background-color: transparent; }",
                    ".slim-scroll .scroll-bar:vertical { -fx-background-color: transparent; -fx-pref-width: 12; "
                            + "-fx-padding: 4 3 4 3; }",
                    ".slim-scroll .scroll-bar:vertical:hover { -fx-padding: 4 1 4 1; }",
                    ".slim-scroll .scroll-bar:horizontal { -fx-background-color: transparent; -fx-pref-height: 12; "
                            + "-fx-padding: 3 4 3 4; }",
                    ".slim-scroll .scroll-bar:horizontal:hover { -fx-padding: 1 4 1 4; }",
                    ".slim-scroll .scroll-bar .track, .slim-scroll .scroll-bar .track-background { "
                            + "-fx-background-color: transparent; -fx-border-color: transparent; }",
                    ".slim-scroll .scroll-bar .thumb { -fx-background-color: rgba(36, 87, 166, 0.28); "
                            + "-fx-background-radius: 999; -fx-background-insets: 0; }",
                    ".slim-scroll .scroll-bar .thumb:hover { -fx-background-color: rgba(36, 87, 166, 0.5); }",
                    ".slim-scroll .scroll-bar .thumb:pressed { -fx-background-color: rgba(36, 87, 166, 0.7); }",
                    ".slim-scroll .scroll-bar .increment-button, .slim-scroll .scroll-bar .decrement-button { "
                            + "-fx-background-color: transparent; -fx-padding: 0; -fx-pref-height: 0; -fx-pref-width: 0; }",
                    ".slim-scroll .scroll-bar .increment-arrow, .slim-scroll .scroll-bar .decrement-arrow { "
                            + "-fx-shape: \" \"; -fx-padding: 0; }")
                    .getBytes(StandardCharsets.UTF_8));
}
