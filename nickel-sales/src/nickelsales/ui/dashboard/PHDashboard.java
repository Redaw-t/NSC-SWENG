package nickelsales.ui.dashboard;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import nickelsales.auth.contract.User;
import javafx.scene.layout.HBox; 

public class PHDashboard {

    private final VBox view;

    public PHDashboard(String title, User user, Button changePasswordButton, Button logoutButton) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Label who = new Label("Logged in as " + user.getFullName() + " (" + user.getUserId() + ")");
        Label roles = new Label("Roles: " + user.getRoles());
        Label note = new Label("Note: placeholder lng muna for testing lol.");

        HBox actions = new HBox(12, changePasswordButton, logoutButton);
        actions.setAlignment(Pos.CENTER);

        view = new VBox(16, heading, who, roles, note, actions);
        view.setAlignment(Pos.CENTER);
        view.setStyle("-fx-background-color: white;");
    }

    public Parent getView() {
        return view;
    }
}