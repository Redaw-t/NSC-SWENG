package system.ui;

import app.AppModule;
import app.AppShell;
import app.ComingSoonView;
import app.ScreenContext;
import auth.permission.Permission;
import javafx.scene.Parent;
import system.service.ActivityLogService;
import system.service.InventoryLogService;
import system.service.SyncLogService;

/**
 * "System &amp; sync" (the sidebar's Audit Logs entry): opens {@link SystemScreen} (activity, inventory
 * and sync logs) for users who hold MANAGE_SYSTEM (Admin). Needs the system services to be registered
 * in app.Main. System Status and Backup &amp; Restore are on the Settings screen ({@link SystemSettingsTabs}).
 */
public final class SystemModule implements AppModule {

    @Override
    public String title() {
        return AppShell.SYSTEM;
    }

    @Override
    public String description() {
        return "Activity, inventory and sync logs";
    }

    @Override
    public Permission permission() {
        return Permission.MANAGE_SYSTEM;
    }

    @Override
    public boolean usesShell() {
        return true;
    }

    @Override
    public Parent open(ScreenContext screen) {
        ActivityLogService activity = screen.app().find(ActivityLogService.class).orElse(null);
        InventoryLogService inventory = screen.app().find(InventoryLogService.class).orElse(null);
        SyncLogService sync = screen.app().find(SyncLogService.class).orElse(null);
        if (activity == null || inventory == null || sync == null) {
            return ComingSoonView.notice(title(), "The system module is not connected in this build.");
        }
        return new SystemScreen(screen, activity, inventory, sync).getView();
    }
}
