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
import system.service.InventoryLogService;
import system.service.SaleLineLogService;
import system.service.SyncLogService;

import java.util.Arrays;
import java.util.List;

/**
 * Audit Logs, laid out like the Reports design: a tab strip (Activity Logs | Inventory Logs |
 * Sync Logs | Open Items &amp; Price Edits) above the tab's own screen, inside the app's sidebar frame.
 * System Status and Backup &amp; Restore moved to the Settings screen ({@link SystemSettingsTabs}).
 *
 * The Refresh button under the tabs reloads the shown tab (F5 does the same). No other keyboard shortcuts:
 * this screen is used with the mouse; only POS has shortcuts.
 */
public final class SystemScreen {

    private enum Tab {
        ACTIVITY("Activity Logs"), INVENTORY("Inventory Logs"), SYNC("Sync Logs"),
        SALE_LINES("Open Items & Price Edits");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private final List<SystemPane> panes;
    private final TabBar tabs = new TabBar(Arrays.stream(Tab.values()).map(t -> t.label).toList(),
            number -> show(Tab.values()[number]));
    private final BorderPane body = new BorderPane();
    private final VBox view = new VBox(20);
    private final EventHandler<KeyEvent> shortcuts = this::onKey;

    private SystemPane current;

    public SystemScreen(ScreenContext screen, ActivityLogService activity, InventoryLogService inventory,
                        SyncLogService sync, SaleLineLogService saleLines) {
        this.panes = List.of(new ActivityLogPane(screen, activity), new InventoryLogPane(screen, inventory),
                new SyncLogPane(screen, sync), new SaleLineLogPane(screen, saleLines));

        // The tabs, and under them on the right the Refresh button (F5 does the same; it isn't printed).
        // No other keyboard shortcuts here: these screens are used with the mouse (only POS has shortcuts).
        VBox header = shared.PageHeader.of(tabs.getView(), () -> current.refresh());

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
            return;
        }
        if (e.getCode() == KeyCode.F5) {
            current.refresh();
            e.consume();
        }
    }
}
