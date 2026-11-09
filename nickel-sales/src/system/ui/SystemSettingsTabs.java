package system.ui;

import app.ScreenContext;
import auth.permission.Permission;
import javafx.scene.Parent;
import system.service.BackupService;
import system.service.StoreDetailsService;
import system.service.SystemStatusService;

import java.util.ArrayList;
import java.util.List;

/**
 * The Store Details, System Status and Backup &amp; Restore tabs, shown on the Settings screen (they used to be tabs
 * of the Audit Logs screen). Only for users who hold MANAGE_SYSTEM (Admin) and only when the
 * system services are registered in app.Main; otherwise {@link #forScreen} returns no tabs.
 * The services check the permission again on every call.
 */
public final class SystemSettingsTabs {

    /** One tab: its label, its view, and what to run each time it is shown. */
    public record Tab(String label, Parent view, Runnable refresh) { }

    private SystemSettingsTabs() { }

    /** Store Details, System Status, then Backup &amp; Restore; empty if this user may not see them. */
    public static List<Tab> forScreen(ScreenContext screen) {
        List<Tab> tabs = new ArrayList<>();
        boolean allowed;
        try {
            allowed = screen.app().authz().checkAccess(screen.user(), Permission.MANAGE_SYSTEM);
        } catch (RuntimeException e) {
            allowed = false;
        }
        if (!allowed) {
            return tabs;
        }
        StoreDetailsService store = screen.app().find(StoreDetailsService.class).orElse(null);
        if (store != null) {
            StoreDetailsPane pane = new StoreDetailsPane(screen, store);
            tabs.add(new Tab("Store Details", pane.getView(), pane::refresh));
        }
        SystemStatusService status = screen.app().find(SystemStatusService.class).orElse(null);
        if (status != null) {
            SystemStatusPane pane = new SystemStatusPane(screen, status);
            tabs.add(new Tab("System Status", pane.getView(), pane::refresh));
        }
        BackupService backups = screen.app().find(BackupService.class).orElse(null);
        if (backups != null) {
            BackupPane pane = new BackupPane(screen, backups);
            tabs.add(new Tab("Backup & Restore", pane.getView(), pane::refresh));
        }
        return tabs;
    }
}
