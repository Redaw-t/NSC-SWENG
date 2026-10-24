package app;

import auth.permission.Permission;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Placeholder button for a module that isn't built yet, so the home screen
 * already shows who will get it. Replace it in {@link Modules} with the real
 * module.
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
    public Parent open(ScreenContext screen) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: " + HomeScreen.BLUE + ";");
        Label note = new Label("This screen is not built yet.");
        note.setStyle("-fx-font-size: 16px;");
        Button back = new Button("Back to home");
        back.setDefaultButton(true);
        back.setOnAction(e -> screen.goHome());
        VBox view = new VBox(16, heading, note, back);
        view.setAlignment(Pos.CENTER);
        view.setStyle("-fx-background-color: white;");
        return view;
    }
}
