package auth.ui;

import auth.contract.User;
import auth.contract.UserRole;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import shared.PhTime;
import shared.Styles;
import shared.Theme;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small builders for the User Management screens: avatar, switch, role pills, stat cards, table columns. */
final class UserUi {

    /** Role colours that are not in the main palette. */
    private static final String INVENTORY_PURPLE = "#5B3FA3";
    private static final String CASHIER_SLATE = "#5C6677";

    private UserUi() { }

    /* --------------------------------- roles --------------------------------- */

    static String roleLabel(UserRole role) {
        return switch (role) {
            case ADMIN -> "Admin";
            case CASHIER -> "Cashier";
            case INVENTORY_STAFF -> "Inventory";
        };
    }

    static String roleColour(UserRole role) {
        return switch (role) {
            case ADMIN -> Theme.PRIMARY_BLUE;
            case CASHIER -> CASHIER_SLATE;
            case INVENTORY_STAFF -> INVENTORY_PURPLE;
        };
    }

    /** One coloured role pill (Admin blue, Inventory purple, Cashier slate). */
    static Label rolePill(UserRole role) {
        Label pill = new Label(roleLabel(role));
        Styles.pill(pill, roleColour(role), "white");
        return pill;
    }

    /** The Role cell: one pill per role the user holds. */
    static Node roleCell(User user) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER);
        for (UserRole role : UserRole.values()) {
            if (user.hasRole(role)) {
                box.getChildren().add(rolePill(role));
            }
        }
        return box;
    }

    /* ------------------------------- last sign-in ------------------------------- */

    /** "Today · 8:02 AM", "Yesterday · 5:31 PM", "Oct 28, 2026 · 5:31 PM" or "Never". */
    static String formatLastSignIn(LocalDateTime time) {
        if (time == null) {
            return "Never";
        }
        LocalDate today = PhTime.today();
        String clock = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        if (time.toLocalDate().equals(today)) {
            return "Today \u00B7 " + clock;
        }
        if (time.toLocalDate().equals(today.minusDays(1))) {
            return "Yesterday \u00B7 " + clock;
        }
        return time.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)) + " \u00B7 " + clock;
    }

    /** The Last Sign-in cell; "Never" is shown faint. */
    static Node lastSignInCell(User user) {
        Label label = new Label(formatLastSignIn(user.getLastSignIn()));
        if (user.getLastSignIn() == null) {
            Styles.muted(label);
            label.setOpacity(0.6);
        }
        return label;
    }

    /* ------------------------------ avatar, switch ------------------------------ */

    /** A round avatar with a person outline in it. */
    static Node avatar() {
        Circle background = new Circle(17, Color.web(Theme.PRIMARY_BLUE, 0.14));
        Circle head = new Circle(0, -3, 5);
        head.setFill(Color.TRANSPARENT);
        head.setStroke(Color.web(Theme.PRIMARY_BLUE));
        head.setStrokeWidth(1.6);
        Arc shoulders = new Arc(0, 11, 9, 8, 0, 180);
        shoulders.setType(ArcType.OPEN);
        shoulders.setFill(Color.TRANSPARENT);
        shoulders.setStroke(Color.web(Theme.PRIMARY_BLUE));
        shoulders.setStrokeWidth(1.6);
        return new StackPane(background, new Group(head, shoulders));
    }

    /** A green on / grey off switch. Clicking it calls {@code onClick}; the screen then reloads and redraws it. */
    static Node toggle(boolean on, Runnable onClick) {
        Rectangle track = new Rectangle(38, 22);
        track.setArcWidth(22);
        track.setArcHeight(22);
        track.setFill(on ? Color.web(Theme.SUCCESS) : Color.web(Theme.SECONDARY_TEXT, 0.45));
        Circle knob = new Circle(8.5, Color.WHITE);
        StackPane.setAlignment(knob, on ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        StackPane.setMargin(knob, new Insets(0, 3, 0, 3));
        StackPane box = new StackPane(track, knob);
        box.setMinSize(38, 22);
        box.setPrefSize(38, 22);
        box.setMaxSize(38, 22);
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> onClick.run());
        return box;
    }

    /* ------------------------------ headings, cards ------------------------------ */

    static Label pageTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return label;
    }

    static Label pageSubtitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 17px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        return label;
    }

    /** The white outlined button ("Roles & Permissions"). */
    static Button outlineButton(String text) {
        Button button = new Button(text);
        String base = "-fx-background-radius: 4; -fx-border-radius: 4; -fx-border-color: " + Theme.PRIMARY_BLUE
                + "; -fx-border-width: 1.5; -fx-text-fill: " + Theme.PRIMARY_BLUE + "; -fx-font-size: 15px; "
                + "-fx-font-weight: bold; -fx-padding: 0 22 0 22; -fx-pref-height: 40; -fx-cursor: hand; "
                + "-fx-background-color: ";
        String normal = base + Theme.SURFACE + ";";
        String hover = base + Styles.rgba(Theme.PRIMARY_BLUE, 0.08) + ";";
        button.setStyle(normal);
        button.hoverProperty().addListener((obs, was, is) -> button.setStyle(is ? hover : normal));
        return button;
    }

    /** One of the four summary cards: small heading, big number, small note. */
    static final class StatCard extends VBox {

        private final Label value = new Label();
        private final Label note = new Label();

        StatCard(String heading) {
            super(2);
            Label title = new Label(heading.toUpperCase(Locale.ROOT));
            title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            note.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            getChildren().addAll(title, value, note);
            setPadding(new Insets(16, 20, 16, 20));
            Styles.card(this);
            setPrefWidth(200);
            setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(this, Priority.ALWAYS);
            update("0", "", false);
        }

        /** @param alert true to show the number in red (something needs attention) */
        void update(String number, String caption, boolean alert) {
            value.setText(number);
            value.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: "
                    + (alert ? Theme.ERROR : Theme.PRIMARY_TEXT) + ";");
            note.setText(caption);
        }
    }
}
