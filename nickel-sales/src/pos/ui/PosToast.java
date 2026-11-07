package pos.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import shared.Theme;

/**
 * A small notification card in the top-right corner of the POS screen, over everything else. It
 * does not take the keyboard, so the cashier can keep scanning. It closes by itself after a while,
 * or with its x. A new one replaces the one already showing.
 */
final class PosToast {

    private static final double WIDTH = 520;
    private static final Duration SHOWN_FOR = Duration.seconds(12);
    private static final String KEY = "pos.toast";

    private PosToast() { }

    /** A red-edged warning with a bold title and a sentence or two under it. */
    static void warn(StackPane over, String title, String text) {
        Object old = over.getProperties().remove(KEY);
        if (old instanceof VBox oldCard) {
            over.getChildren().remove(oldCard);
        }

        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.RED + ";");
        heading.setWrapText(true);
        HBox.setHgrow(heading, Priority.ALWAYS);
        heading.setMaxWidth(Double.MAX_VALUE);
        Button close = new Button("✕");
        close.setFocusTraversable(false);
        close.setStyle("-fx-background-color: transparent; -fx-font-size: 16px; -fx-cursor: hand; "
                + "-fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        HBox top = new HBox(8, heading, close);
        top.setAlignment(Pos.TOP_LEFT);

        Label body = new Label(text);
        body.setWrapText(true);
        body.setStyle("-fx-font-size: 16px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");

        VBox card = new VBox(6, top, body);
        card.setPadding(new Insets(14, 14, 16, 18));
        card.setMinWidth(WIDTH);
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + PosScreen.RED + "; -fx-border-width: 0 0 0 6;");
        card.setEffect(new DropShadow(16, Color.rgb(0, 0, 0, 0.25)));
        card.setPickOnBounds(false);
        StackPane.setAlignment(card, Pos.TOP_RIGHT);
        StackPane.setMargin(card, new Insets(60, 20, 0, 0));   // just under the status bar

        Runnable dismiss = () -> {
            FadeTransition fade = new FadeTransition(Duration.millis(250), card);
            fade.setToValue(0);
            fade.setOnFinished(e -> {
                over.getChildren().remove(card);
                over.getProperties().remove(KEY, card);
            });
            fade.play();
        };
        close.setOnAction(e -> dismiss.run());
        PauseTransition wait = new PauseTransition(SHOWN_FOR);
        wait.setOnFinished(e -> dismiss.run());

        over.getChildren().add(card);
        over.getProperties().put(KEY, card);
        wait.play();
    }
}
