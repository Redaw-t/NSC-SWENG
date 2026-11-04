package system.ui;

import app.ScreenContext;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import shared.Styles;
import shared.TabBar;
import system.service.ActivityLogService;
import system.service.BackupService;
import system.service.InventoryLogService;
import system.service.SyncLogService;
import system.service.SystemStatusService;

import java.util.Arrays;
import java.util.List;

/**
 * Audit &amp; System, laid out like the Reports design: a tab strip (Activity Logs | Inventory Logs |
 * Sync Logs | System Status | Backup &amp; Restore) above the tab's own screen, inside the app's
 * sidebar frame.
 *
 * Keyboard: Ctrl+1 to Ctrl+5 open the tabs, Ctrl+F goes to the search box, Ctrl+E exports and F5
 * reloads. They work wherever the focus is on this screen, and not while a pop-up is open.
 */
public final class SystemScreen {

    private enum Tab {
        ACTIVITY("Activity Logs"), INVENTORY("Inventory Logs"), SYNC("Sync Logs"), STATUS("System Status"),
        BACKUP("Backup & Restore");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final List<KeyCode> TAB_KEYS = List.of(KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3,
            KeyCode.DIGIT4, KeyCode.DIGIT5);

    private final List<SystemPane> panes;
    private final TabBar tabs = new TabBar(Arrays.stream(Tab.values()).map(t -> t.label).toList(),
            number -> show(Tab.values()[number]));
    private final BorderPane body = new BorderPane();
    private final VBox view = new VBox(20);
    private final EventHandler<KeyEvent> shortcuts = this::onKey;

    private SystemPane current;

    public SystemScreen(ScreenContext screen, ActivityLogService activity, InventoryLogService inventory,
                        SyncLogService sync, SystemStatusService status, BackupService backups) {
        this.panes = List.of(new ActivityLogPane(screen, activity), new InventoryLogPane(screen, inventory),
                new SyncLogPane(screen, sync), new SystemStatusPane(screen, status), new BackupPane(screen, backups));

        Label hint = new Label("Ctrl+1 to Ctrl+5: tabs   |   Ctrl+F: search   |   Ctrl+E: export   |   F5: refresh");
        Styles.muted(hint);
        VBox header = new VBox(6, tabs.getView(), hint);
        header.setAlignment(Pos.CENTER_RIGHT);

        view.getChildren().addAll(header, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        // On the scene, so the keys work when the focus is in the sidebar too; only while this screen is shown.
        view.sceneProperty().addListener((obs, old, scene) -> {
            if (old != null) {
                old.removeEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
            }
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
            }
        });
        show(Tab.ACTIVITY);
    }

    public Parent getView() {
        return view;
    }

    private void show(Tab tab) {
        tabs.select(tab.ordinal());
        current = panes.get(tab.ordinal());
        body.setCenter(current.getView());
        current.refresh();
    }

    private void onKey(KeyEvent e) {
        if (view.isDisabled()) {
            return;   // a restore is running
        }
        int tab = TAB_KEYS.indexOf(e.getCode());
        if (e.getCode() == KeyCode.F5) {
            current.refresh();
        } else if (e.isShortcutDown() && tab >= 0) {
            show(Tab.values()[tab]);
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
            current.focusSearch();
        } else if (e.isShortcutDown() && e.getCode() == KeyCode.E) {
            current.export();
        } else {
            return;
        }
        e.consume();
    }
}
