package app;

import auth.contract.User;
import auth.contract.UserRole;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The screen after login: one big button per module the user may use
 * (chosen by {@link Modules#visibleFor}), plus Change password and Log out.
 * Number keys 1-9 open the matching button, so it works without a mouse.
 */
public final class HomeScreen {

    public static final String BLUE = "#29529a";
    public static final String RED = "#d05c5c";

    private static final String TILE_STYLE =
            "-fx-background-color: white; -fx-border-color: " + BLUE + "; -fx-border-width: 2; "
                    + "-fx-border-radius: 12; -fx-background-radius: 12; -fx-cursor: hand; -fx-padding: 16;";
    private static final String TILE_FOCUSED_STYLE = TILE_STYLE.replace("-fx-background-color: white",
            "-fx-background-color: #e8eef8").replace("-fx-border-width: 2", "-fx-border-width: 3");

    private final BorderPane view = new BorderPane();

    /**
     * @param terminalId     shown in the header
     * @param modules        the buttons to show, in order (already filtered for this user)
     * @param onOpen         called with the module whose button was pressed
     * @param footerButtons  e.g. Change password, Log out
     */
    public HomeScreen(User user, String terminalId, List<AppModule> modules, Consumer<AppModule> onOpen,
                      Button... footerButtons) {
        Label title = new Label("NICKEL SALES CENTER");
        title.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");
        Label who = new Label("Logged in as " + user.getFullName() + " (" + user.getUserId() + ")  ·  "
                + roleNames(user) + "  ·  Terminal " + terminalId);
        who.setStyle("-fx-font-size: 15px; -fx-text-fill: #444444;");
        VBox header = new VBox(6, title, who);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(40, 20, 20, 20));

        TilePane tiles = new TilePane(20, 20);
        tiles.setAlignment(Pos.CENTER);
        tiles.setPrefColumns(Math.min(3, Math.max(1, modules.size())));
        tiles.setPadding(new Insets(20));
        for (int i = 0; i < modules.size(); i++) {
            tiles.getChildren().add(tile(i + 1, modules.get(i), onOpen));
        }

        Parent center;
        if (modules.isEmpty()) {
            Label none = new Label("Your account has no screens to open. Ask an admin to check your role.");
            none.setStyle("-fx-font-size: 16px;");
            center = none;
        } else {
            center = tiles;
        }

        HBox footer = new HBox(12, footerButtons);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(20, 20, 40, 20));

        view.setTop(header);
        view.setCenter(center);
        view.setBottom(footer);
        view.setStyle("-fx-background-color: white;");

        // 1-9 open the matching button
        view.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode().isDigitKey() && !e.isControlDown() && !e.isAltDown()) {
                int n = e.getCode().getCode() - (e.getCode().isKeypadKey() ? 96 : 48);   // NUMPADn / DIGITn
                if (n >= 1 && n <= modules.size()) {
                    e.consume();
                    onOpen.accept(modules.get(n - 1));
                }
            }
        });
    }

    public Parent getView() {
        return view;
    }

    /** Puts keyboard focus on the first button. Call after the screen is shown. */
    public void focusFirst() {
        if (view.getCenter() instanceof TilePane tiles && !tiles.getChildren().isEmpty()) {
            tiles.getChildren().get(0).requestFocus();
        }
    }

    private static Button tile(int number, AppModule module, Consumer<AppModule> onOpen) {
        Label name = new Label(number + "   " + module.title());
        name.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");
        Label description = new Label(module.description());
        description.setStyle("-fx-font-size: 13px; -fx-text-fill: #555555;");
        description.setWrapText(true);
        VBox content = new VBox(6, name, description);
        content.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.setGraphic(content);
        button.setPrefSize(360, 110);
        button.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setStyle(TILE_STYLE);
        button.focusedProperty().addListener((obs, was, is) -> button.setStyle(is ? TILE_FOCUSED_STYLE : TILE_STYLE));
        button.setAccessibleText(module.title() + ": " + module.description());
        button.setOnAction(e -> onOpen.accept(module));
        return button;
    }

    private static String roleNames(User user) {
        if (user.getRoles().isEmpty()) {
            return "no role";
        }
        return user.getRoles().stream().sorted().map(HomeScreen::roleName).collect(Collectors.joining(", "));
    }

    private static String roleName(UserRole role) {
        return switch (role) {
            case ADMIN -> "Admin";
            case CASHIER -> "Cashier";
            case INVENTORY_STAFF -> "Inventory staff";
        };
    }
}
