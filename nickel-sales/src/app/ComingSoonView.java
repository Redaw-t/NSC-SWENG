package app;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import shared.Styles;
import shared.Theme;

/**
 * A centred card for screens (or tabs) that are not built yet, so the layout is
 * already in place.
 */
public final class ComingSoonView {

    private ComingSoonView() { }

    /** A card with a "Coming soon" badge. */
    public static Parent create(String title, String message) {
        return build(title, message, "Coming soon");
    }

    /** The same card without the badge: for "not available in this build" notices. */
    public static Parent notice(String title, String message) {
        return build(title, message, null);
    }

    private static Parent build(String title, String message, String badge) {
        Label heading = new Label(title);
        Styles.cardTitle(heading);
        Label note = new Label(message);
        Styles.cardText(note);
        note.setWrapText(true);
        note.setTextAlignment(TextAlignment.CENTER);
        note.setMaxWidth(440);

        VBox card = new VBox(14);
        Styles.card(card);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(48, 56, 48, 56));
        card.setMaxWidth(580);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        if (badge != null) {
            Label pill = new Label(badge);
            Styles.pill(pill, Theme.WARNING, "white");
            card.getChildren().add(pill);
        }
        card.getChildren().addAll(heading, note);

        StackPane holder = new StackPane(card);
        holder.setAlignment(Pos.CENTER);
        holder.setPadding(new Insets(24));
        return holder;
    }
}
