package nickelsales.ui.dashboard;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import nickelsales.auth.contract.User;

public class PHDashboard {

    private final VBox view;

    public PHDashboard(String title, User user, Button logoutButton) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Label who = new Label("Logged in as " + user.getFullName() + " (" + user.getUserId() + ")");
        Label roles = new Label("Roles: " + user.getRoles());
        Label note = new Label("Note: placeholder lng muna for testing lol.");

        view = new VBox(16, heading, who, roles, note, logoutButton);
        view.setAlignment(Pos.CENTER);
        view.setStyle("-fx-background-color: white;");
    }

    public Parent getView() {
        return view;
    }
}