package auth.ui;

import app.ComingSoonView;
import auth.account.UserAdminService;
import auth.permission.AuthorizationService;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import shared.Styles;

import java.util.EnumMap;
import java.util.Map;

/**
 * User management, laid out like the design: a tab strip (Users | Sales) above the tab's own
 * screen. It is shown inside the app's sidebar frame (see app.AppShell), so it has no Back
 * button; the sidebar's Dashboard goes home.
 *
 * The Users tab is {@link UsersPane}; Sales is not built yet and shows a "Coming soon" card.
 */
public class UserManagementScreen {

    private enum Tab {
        USERS("Users"), SALES("Sales");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private final UsersPane usersPane;
    private final Parent salesPlaceholder = ComingSoonView.create("Sales",
            "Sales per user (who sold what, and when) isn't built yet.");

    private final Map<Tab, Button> tabButtons = new EnumMap<>(Tab.class);
    private final BorderPane body = new BorderPane();
    private final VBox view = new VBox(20);

    public UserManagementScreen(UserAdminService admin, AuthorizationService authz, String sessionId,
                                Runnable onBack) {
        this.usersPane = new UsersPane(admin, authz, sessionId, onBack);

        HBox bar = new HBox();
        Styles.tabBar(bar);
        bar.setAlignment(Pos.CENTER);
        boolean first = true;
        for (Tab tab : Tab.values()) {
            if (!first) {
                Label divider = new Label("|");
                Styles.tabDivider(divider);
                bar.getChildren().add(divider);
            }
            first = false;
            Button button = new Button(tab.label);
            Styles.tab(button, false);
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setOnAction(e -> show(tab));
            tabButtons.put(tab, button);
            bar.getChildren().add(button);
        }

        view.getChildren().addAll(bar, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        show(Tab.USERS);   // if loading fails, the message shows and the sidebar still works
    }

    public Parent getView() {
        return view;
    }

    private void show(Tab tab) {
        tabButtons.forEach((t, button) -> Styles.tab(button, t == tab));
        switch (tab) {
            case USERS -> {
                body.setCenter(usersPane.getView());
                usersPane.refresh();
            }
            case SALES -> body.setCenter(salesPlaceholder);
        }
    }
}
