package shared;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The grey tab strip at the top of a screen: "Products | Categories | Suppliers | Barcode".
 * The screen decides what each tab shows; this only draws the strip, calls {@code onSelect}
 * with the clicked tab's number, and bolds the selected tab when told to ({@link #select}).
 */
public final class TabBar {

    private final HBox view = new HBox();
    private final List<Button> tabs = new ArrayList<>();

    /** @param onSelect called with the tab's number (0 = first) when a tab is clicked */
    public TabBar(List<String> labels, IntConsumer onSelect) {
        Styles.tabBar(view);
        view.setAlignment(Pos.CENTER);
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                Label divider = new Label("|");
                Styles.tabDivider(divider);
                view.getChildren().add(divider);
            }
            int number = i;
            Button tab = new Button(labels.get(i));
            Styles.tab(tab, false);
            tab.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tab, Priority.ALWAYS);
            tab.setOnAction(e -> onSelect.accept(number));
            tabs.add(tab);
            view.getChildren().add(tab);
        }
    }

    public HBox getView() {
        return view;
    }

    /** Bolds tab {@code number} and un-bolds the others. Does not call {@code onSelect}. */
    public void select(int number) {
        for (int i = 0; i < tabs.size(); i++) {
            Styles.tab(tabs.get(i), i == number);
        }
    }
}
