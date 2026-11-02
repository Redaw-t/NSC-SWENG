package app;

import auth.permission.Permission;
import javafx.scene.Parent;

import java.util.Objects;

/**
 * Placeholder button for a module that isn't built yet, so the home screen
 * already shows who will get it. Replace it in {@link Modules} with the real
 * module. Its screen is a "Coming soon" card inside the sidebar shell.
 */
public final class ComingSoonModule implements AppModule {

    private final String title;
    private final String description;
    private final Permission permission;

    public ComingSoonModule(String title, String description, Permission permission) {
        this.title = Objects.requireNonNull(title, "title");
        this.description = Objects.requireNonNull(description, "description");
        this.permission = Objects.requireNonNull(permission, "permission");
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Permission permission() {
        return permission;
    }

    @Override
    public boolean usesShell() {
        return true;
    }

    @Override
    public Parent open(ScreenContext screen) {
        return ComingSoonView.create(title, "This screen isn't built yet. Planned: " + description + ".");
    }
}
