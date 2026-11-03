package app;

import auth.ui.ChangePasswordDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import shared.Styles;
import shared.Theme;

/**
 * The Settings screen (sidebar, bottom entry). Open to every logged-in user:
 * - Change password: the user's own password.
 * - System &amp; sync: only for users who may open it (admins); it is a "coming soon"
 *   module, reached from here.
 */
public final class SettingsScreen {

    private SettingsScreen() { }

    /**
     * @param openSystem opens the System &amp; sync module; null if this user may not open it
     */
    public static Parent create(Stage stage, AppContext app, String sessionId, Runnable openSystem) {
        Label heading = new Label("Settings");
        Styles.cardTitle(heading);

        Button changePassword = new Button("Change password");
        Styles.primaryButton(changePassword);
        changePassword.setOnAction(e -> new ChangePasswordDialog(stage, app.accounts(), sessionId).show());

        VBox view = new VBox(20, heading,
                section("Account", "Change the password you use to log in.", changePassword));
        if (openSystem != null) {
            Button system = new Button("Open System & sync");
            Styles.primaryButton(system);
            system.setOnAction(e -> openSystem.run());
            view.getChildren().add(section("System & sync", "Terminal sync, logs and backup.", system));
        }
        view.setAlignment(Pos.TOP_LEFT);
        return view;
    }

    private static VBox section(String title, String text, Button action) {
        Label name = new Label(title);
        name.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label note = new Label(text);
        Styles.cardText(note);
        note.setWrapText(true);
        VBox card = new VBox(10, name, note, action);
        Styles.card(card);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setMaxWidth(560);
        return card;
    }
}
