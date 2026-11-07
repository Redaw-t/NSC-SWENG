package shared;

import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * One small stylesheet added to every window of the app (main window, pop-ups, menus, drop-down
 * lists), for looks that must be the same everywhere:
 *
 * <ul>
 *   <li><b>Focus ring</b>: when you move with Tab (or the arrow keys), the focused button gets a blue
 *       ring just outside its edge and a soft blue glow; a mouse click shows nothing extra
 *       ({@code :focus-visible}). Buttons are coloured with inline styles, which hide JavaFX's own
 *       ring. The ring is drawn outside the button and takes no space, so nothing moves.</li>
 *   <li><b>Scroll bars</b>: every scroll bar (tables, lists, scroll panes, text areas) looks like the
 *       Dashboard's: no arrows, no track, a slim soft-blue rounded thumb that widens and darkens a
 *       little under the mouse.</li>
 * </ul>
 */
public final class GlobalStyles {

    /** The Dashboard's scroll bar thumb colour, and darker for hover / drag. */
    private static final String THUMB = Styles.rgba(Theme.PRIMARY_BLUE, 0.28);
    private static final String THUMB_HOVER = Styles.rgba(Theme.PRIMARY_BLUE, 0.5);
    private static final String THUMB_PRESSED = Styles.rgba(Theme.PRIMARY_BLUE, 0.7);

    private static final String CSS = "data:text/css;base64," + Base64.getEncoder().encodeToString(
            String.join("\n",
                    /* ---------------- focus ring ---------------- */
                    ".button:focus-visible, .toggle-button:focus-visible, .hyperlink:focus-visible, "
                            + ".check-box:focus-visible, .radio-button:focus-visible, .menu-button:focus-visible {",
                    "    -fx-border-color: " + Theme.PRIMARY_BLUE + ";",
                    "    -fx-border-width: 2.5;",
                    "    -fx-border-insets: -4;",          // outside the button: no layout change
                    "    -fx-border-radius: 10;",
                    "    -fx-effect: dropshadow(gaussian, " + Styles.rgba(Theme.PRIMARY_BLUE, 0.45) + ", 12, 0.35, 0, 0);",
                    "}",

                    /* ---------------- scroll bars (same as the Dashboard) ---------------- */
                    ".scroll-bar { -fx-background-color: transparent; -fx-background-insets: 0; }",
                    ".scroll-bar:vertical { -fx-pref-width: 12; -fx-padding: 4 3 4 3; }",
                    ".scroll-bar:vertical:hover { -fx-padding: 4 1 4 1; }",
                    ".scroll-bar:horizontal { -fx-pref-height: 12; -fx-padding: 3 4 3 4; }",
                    ".scroll-bar:horizontal:hover { -fx-padding: 1 4 1 4; }",
                    ".scroll-bar > .track, .scroll-bar > .track-background { -fx-background-color: transparent; "
                            + "-fx-border-color: transparent; }",
                    ".scroll-bar > .thumb { -fx-background-color: " + THUMB + "; -fx-background-radius: 999; "
                            + "-fx-background-insets: 0; }",
                    ".scroll-bar > .thumb:hover { -fx-background-color: " + THUMB_HOVER + "; }",
                    ".scroll-bar > .thumb:pressed { -fx-background-color: " + THUMB_PRESSED + "; }",
                    ".scroll-bar > .increment-button, .scroll-bar > .decrement-button { -fx-background-color: transparent; "
                            + "-fx-padding: 0; -fx-pref-height: 0; -fx-pref-width: 0; }",
                    ".scroll-bar > .increment-button > .increment-arrow, .scroll-bar > .decrement-button > .decrement-arrow "
                            + "{ -fx-shape: \" \"; -fx-padding: 0; }",
                    // the little square where a vertical and a horizontal bar meet
                    ".scroll-pane > .corner, .virtual-flow > .corner, .text-area .corner { -fx-background-color: transparent; }")
                    .getBytes(StandardCharsets.UTF_8));

    private GlobalStyles() { }

    /** Adds the stylesheet to every window that is open now or opens later. Call once at start-up. */
    public static void installEverywhere() {
        for (Window w : Window.getWindows()) {
            watch(w);
        }
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    watch(w);
                }
            }
        });
    }

    /** Adds the stylesheet to one scene (e.g. a scene shown before {@link #installEverywhere}). */
    public static void install(Scene scene) {
        if (scene != null && !scene.getStylesheets().contains(CSS)) {
            scene.getStylesheets().add(CSS);
        }
    }

    private static void watch(Window window) {
        install(window.getScene());
        window.sceneProperty().addListener((obs, old, scene) -> install(scene));
    }
}
