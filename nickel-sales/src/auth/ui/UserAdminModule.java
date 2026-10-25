package auth.ui;

import app.AppModule;
import app.ScreenContext;
import auth.permission.Permission;
import javafx.scene.Parent;

/** "Manage users" on the home screen: opens {@link UserManagementScreen} (admins only). */
public final class UserAdminModule implements AppModule {

    @Override
    public String title() {
        return "Manage users";
    }

    @Override
    public String description() {
        return "Add, edit, activate users";
    }

    @Override
    public Permission permission() {
        return Permission.MANAGE_USERS;
    }

    @Override
    public Parent open(ScreenContext screen) {
        return new UserManagementScreen(screen.app().admin(), screen.sessionId(), screen::goHome).getView();
    }
}
