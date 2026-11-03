package auth.ui;

import app.ComingSoonView;
import auth.account.UserAdminService;
import auth.permission.AuthorizationService;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import shared.TabBar;

import java.util.Arrays;

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

    private final TabBar tabs = new TabBar(Arrays.stream(Tab.values()).map(t -> t.label).toList(),
            number -> show(Tab.values()[number]));
    private final BorderPane body = new BorderPane();
    private final VBox view = new VBox(20);

    public UserManagementScreen(UserAdminService admin, AuthorizationService authz, String sessionId,
                                Runnable onBack) {
        this.usersPane = new UsersPane(admin, authz, sessionId, onBack);

        view.getChildren().addAll(tabs.getView(), body);
        VBox.setVgrow(body, Priority.ALWAYS);
        show(Tab.USERS);   // if loading fails, the message shows and the sidebar still works
    }

    public Parent getView() {
        return view;
    }

    private void show(Tab tab) {
        tabs.select(tab.ordinal());
        switch (tab) {
            case USERS -> {
                body.setCenter(usersPane.getView());
                usersPane.refresh();
            }
            case SALES -> body.setCenter(salesPlaceholder);
        }
    }
}
