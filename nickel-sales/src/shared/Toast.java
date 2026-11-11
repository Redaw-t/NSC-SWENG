package shared;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * A small notification card in the top-right corner of the app window, for MAJOR events that must
 * not rely on a line of text in a screen (product saved, stock updated, backup done, export failed...).
 * It never takes the keyboard, closes itself after a few seconds (errors stay longer) or when clicked,
 * and a new one replaces the one showing. Field validation stays next to its field; use a Modal
 * ({@link Modal#notice}) when the user must acknowledge something (database down, account locked).
 */
public final class Toast {

    public enum Kind { SUCCESS, ERROR, INFO }

    private static final double WIDTH = 380;
    private static Popup current;
    private static PauseTransition timer;

    private Toast() { }

    public static void success(Window owner, String title, String text) {
        show(owner, Kind.SUCCESS, title, text);
    }

    public static void error(Window owner, String title, String text) {
        show(owner, Kind.ERROR, title, text);
    }

    public static void info(Window owner, String title, String text) {
        show(owner, Kind.INFO, title, text);
    }

    public static void show(Window owner, Kind kind, String title, String text) {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        dismiss();
        String colour = switch (kind) {
            case SUCCESS -> Theme.SUCCESS;
            case ERROR -> Theme.ERROR;
            case INFO -> Theme.INFORMATION;
        };

        Label heading = new Label(title);
        heading.setWrapText(true);
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + colour + ";");
        VBox card = new VBox(4, heading);
        if (text != null && !text.isBlank()) {
            Label body = new Label(text);
            body.setWrapText(true);
            body.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
            card.getChildren().add(body);
        }
        card.setPadding(new Insets(14, 18, 16, 18));
        card.setMinWidth(WIDTH);
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 8; "
                + "-fx-border-color: transparent transparent transparent " + colour + "; "
                + "-fx-border-width: 0 0 0 6; -fx-border-radius: 8;");
        card.setEffect(new DropShadow(14, Color.rgb(0, 0, 0, 0.25)));
        card.setAccessibleText(title + (text == null ? "" : ". " + text));

        StackPane holder = new StackPane(card);
        holder.setPadding(new Insets(16));   // room for the shadow
        holder.setStyle("-fx-background-color: transparent;");

        Popup popup = new Popup();
        popup.getContent().add(holder);
        popup.setAutoFix(true);
        popup.setHideOnEscape(false);
        holder.setOnMouseClicked(e -> dismiss());
        popup.show(owner, owner.getX() + owner.getWidth() - WIDTH - 56, owner.getY() + 56);
        current = popup;

        timer = new PauseTransition(Duration.seconds(kind == Kind.ERROR ? 8 : 4));
        timer.setOnFinished(e -> dismiss());
        timer.play();
    }

    /** Closes the toast that is showing, if any. */
    public static void dismiss() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (current != null) {
            current.hide();
            current = null;
        }
    }
}
