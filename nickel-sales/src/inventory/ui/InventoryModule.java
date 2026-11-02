package inventory.ui;

import app.AppModule;
import app.HomeScreen;
import app.ScreenContext;
import auth.permission.Permission;
import inventory.service.InventoryService;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * "Products &amp; inventory" on the home screen (inventory staff and admins).
 * Needs an {@link InventoryService} registered in the AppContext
 * ({@code app.register(InventoryService.class, ...)}); says so if it is missing.
 */
public final class InventoryModule implements AppModule {

    @Override
    public String title() {
        return "Products & inventory";
    }

    @Override
    public String description() {
        return "Products, prices, stock in/out, barcodes";
    }

    @Override
    public Permission permission() {
        return Permission.UPDATE_INVENTORY;
    }

    @Override
    public Parent open(ScreenContext screen) {
        Optional<InventoryService> service = screen.app().find(InventoryService.class);
        if (service.isEmpty()) {
            Label heading = new Label("Inventory");
            heading.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: " + HomeScreen.BLUE + ";");
            Label note = new Label("Inventory is not connected on this terminal yet.");
            note.setStyle("-fx-font-size: 16px;");
            Button back = new Button("Back to home");
            back.setDefaultButton(true);
            back.setOnAction(e -> screen.goHome());
            VBox view = new VBox(16, heading, note, back);
            view.setAlignment(Pos.CENTER);
            view.setStyle("-fx-background-color: white;");
            return view;
        }
        return new InventoryScreen(screen, service.get()).getView();
    }
}
