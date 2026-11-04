package app;

import auth.contract.User;
import auth.permission.AuthorizationService;
import auth.permission.Permission;
import auth.ui.UserAdminModule;
import inventory.ui.InventoryModule;
import pos.ui.PosModule;
import product.ui.ProductModule;
import system.ui.SystemModule;

import java.util.List;
import java.util.Objects;

/**
 * THE ONE PLACE where modules put their button on the home screen.
 *
 * To add your module: write a class that implements {@link AppModule} in
 * your own package (e.g. inventory.ui.InventoryModule) and replace its
 * "coming soon" line below with {@code new InventoryModule()}. You don't need
 * to touch the home screen, the router, or any other module's code.
 *
 * Buttons appear in this order, numbered 1, 2, 3 ... (the number key opens it).
 */
public final class Modules {

    private Modules() { }

    public static List<AppModule> all() {
        return List.of(
                // POS & Sales
                new PosModule(),
                // Product Management (catalog, prices, categories, suppliers)
                new ProductModule(),
                // Inventory (stock in/out)
                new InventoryModule(),
                // Transaction & Reports: replace with your module
                new ComingSoonModule("Reports", "Sales and inventory reports", Permission.VIEW_REPORTS),
                // User & Access Management
                new UserAdminModule(),
                // Audit & System (activity logs; sync, status and backup follow)
                new SystemModule());
    }

    /** The modules {@code user} may use right now, in menu order (empty if inactive). */
    public static List<AppModule> visibleFor(User user, AuthorizationService authz, List<AppModule> modules) {
        Objects.requireNonNull(authz, "authz");
        return modules.stream().filter(m -> authz.checkAccess(user, m.permission())).toList();
    }
}
