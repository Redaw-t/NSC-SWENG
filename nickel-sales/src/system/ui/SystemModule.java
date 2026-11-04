package system.ui;

import app.AppModule;
import app.AppShell;
import app.ComingSoonView;
import app.ScreenContext;
import auth.permission.Permission;
import javafx.scene.Parent;
import system.service.ActivityLogService;
import system.service.BackupService;
import system.service.InventoryLogService;
import system.service.SyncLogService;
import system.service.SystemStatusService;

/**
 * "System &amp; sync" (the sidebar's Audit Logs entry): opens {@link SystemScreen} for users who hold
 * MANAGE_SYSTEM (Admin). Needs the system services to be registered in app.Main.
 */
public final class SystemModule implements AppModule {

    @Override
    public String title() {
        return AppShell.SYSTEM;
    }

    @Override
    public String description() {
        return "Activity logs, sync, system status, backup";
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
        SystemStatusService status = screen.app().find(SystemStatusService.class).orElse(null);
        BackupService backups = screen.app().find(BackupService.class).orElse(null);
        if (activity == null || inventory == null || sync == null || status == null || backups == null) {
            return ComingSoonView.notice(title(), "The system module is not connected in this build.");
        }
        return new SystemScreen(screen, activity, inventory, sync, status, backups).getView();
    }
}
