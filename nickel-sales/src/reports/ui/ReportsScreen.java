package reports.ui;

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

import java.util.List;

/**
 * Reports: a tab strip above the chosen report, inside the app's sidebar frame. It is given
 * only the tabs this user may open ({@link ReportsModule} decides), so a hidden report has no
 * tab at all. F5 loads the shown report again.
 */
final class ReportsScreen {

    /** One tab: its label and its pane. */
    record Tab(String label, ReportsPane pane) { }

    private final List<Tab> tabs;
    private final TabBar bar;
    private final BorderPane body = new BorderPane();
    private final VBox view = new VBox(20);
    private final EventHandler<KeyEvent> shortcuts = this::onKey;

    private ReportsPane current;

    ReportsScreen(List<Tab> tabs) {
        if (tabs.isEmpty()) {
            throw new IllegalArgumentException("at least one tab");
        }
        this.tabs = List.copyOf(tabs);
        this.bar = new TabBar(this.tabs.stream().map(Tab::label).toList(), this::show);

        Label hint = new Label("F5: refresh");
        Styles.muted(hint);
        VBox header = new VBox(6, bar.getView(), hint);
        header.setAlignment(Pos.CENTER_RIGHT);

        view.getChildren().addAll(header, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        // On the scene, so F5 works wherever the focus is; only while this screen is shown.
        view.sceneProperty().addListener((obs, old, scene) -> {
            if (old != null) {
                old.removeEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
            }
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
            }
        });
        show(0);
    }

    Parent getView() {
        return view;
    }

    private void show(int number) {
        bar.select(number);
        current = tabs.get(number).pane();
        body.setCenter(current.getView());
        current.refresh();
    }

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.F5) {
            current.refresh();
            e.consume();
        }
    }
}
