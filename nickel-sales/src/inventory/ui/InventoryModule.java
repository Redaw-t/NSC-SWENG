package inventory.ui;

import app.AppModule;
import app.ComingSoonView;
import app.ScreenContext;
import auth.permission.Permission;
import inventory.service.InventoryService;
import javafx.scene.Parent;

import java.util.Optional;

/**
 * "Inventory" on the home screen (inventory staff and admins): opens {@link InventoryScreen}.
 * Needs an {@link InventoryService} registered in the AppContext
 * ({@code app.register(InventoryService.class, ...)}); says so if it is missing.
 */
public final class InventoryModule implements AppModule {

    @Override
    public String title() {
        return "Inventory";
    }

    @Override
    public String description() {
        return "Stock in/out, adjustments, movement history";
    }

    @Override
    public Permission permission() {
        return Permission.UPDATE_INVENTORY;
    }

    /** Shown inside the sidebar frame ({@link app.AppShell}); the sidebar's Dashboard entry goes home. */
    @Override
    public boolean usesShell() {
        return true;
    }

    @Override
    public Parent open(ScreenContext screen) {
        Optional<InventoryService> service = screen.app().find(InventoryService.class);
        if (service.isEmpty()) {
            return ComingSoonView.notice("Inventory", "Inventory is not connected on this terminal yet.");
        }
        return new InventoryScreen(screen, service.get()).getView();
    }
}
