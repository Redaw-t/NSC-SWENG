package shared;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.Objects;

/**
 * A screen's filters, the same on every screen:
 * - LIVE: the list updates as soon as a filter changes (a date picked, a choice made, typing in a search
 *   box after a short pause), so there is no Apply button;
 * - RESET: puts every filter back to how the screen opened and clears what was typed;
 * - REFRESH ICON: hidden until another register's changes arrive by sync ({@link DataChanged}); then it
 *   shows, and a click loads the list again from the database, keeping the filters as they are.
 *
 * <pre>
 *   LiveFilters filters = new LiveFilters(this::load);
 *   filters.watch(dateFrom, dateTo, cashier, searchBox);
 *   toolbar.getChildren().addAll(filters.resetButton(() -> { dateFrom.setValue(today); ... }), filters.refreshIcon());
 * </pre>
 */
public final class LiveFilters {

    /** How long typing must pause before the list updates. */
    static final Duration TYPING_PAUSE = Duration.millis(300);

    private final Runnable reload;
    private boolean muted;

    /** @param reload loads the list with the current filters (from the database) */
    public LiveFilters(Runnable reload) {
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    /**
     * Reloads when any of {@code controls} changes: text boxes after a short pause, date pickers, drop-downs,
     * search pickers and check boxes at once. Anything else that is an ObservableValue also works.
     */
    public LiveFilters watch(Object... controls) {
        for (Object control : controls) {
            if (control instanceof TextInputControl text) {
                PauseTransition pause = new PauseTransition(TYPING_PAUSE);
                pause.setOnFinished(e -> changed());
                text.textProperty().addListener((o, was, is) -> {
                    if (!muted) {
                        pause.playFromStart();
                    }
                });
            } else if (control instanceof ComboBoxBase<?> box) {   // DatePicker, ComboBox
                box.valueProperty().addListener((o, was, is) -> changed());
            } else if (control instanceof SearchPicker<?> picker) {
                picker.valueProperty().addListener((o, was, is) -> changed());
            } else if (control instanceof CheckBox check) {
                check.selectedProperty().addListener((o, was, is) -> changed());
            } else if (control instanceof ObservableValue<?> value) {
                value.addListener((o, was, is) -> changed());
            } else if (control != null) {
                throw new IllegalArgumentException("Can't watch " + control.getClass().getSimpleName());
            }
        }
        return this;
    }

    private void changed() {
        if (!muted) {
            reload.run();
        }
    }

    /** Runs {@code changes} to the filter controls without reloading (e.g. refreshing a drop-down's choices). */
    public void quietly(Runnable changes) {
        boolean was = muted;
        muted = true;
        try {
            changes.run();
        } finally {
            muted = was;
        }
    }

    /**
     * Puts the filters back ({@code defaults} sets every control to how the screen opened and clears the
     * text boxes) and loads the list once.
     */
    public void reset(Runnable defaults) {
        muted = true;
        try {
            defaults.run();
        } finally {
            muted = false;
        }
        reload.run();
    }

    /** The grey Reset button: {@link #reset} with {@code defaults}. */
    public Button resetButton(Runnable defaults) {
        Button button = new Button("Reset");
        String base = "-fx-background-radius: 4; -fx-font-size: 15px; -fx-pref-height: 40; -fx-padding: 0 20 0 20; "
                + "-fx-cursor: hand; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-background-color: ";
        String normal = base + Theme.BORDER + ";";
        String hover = base + Styles.darker(Theme.BORDER) + ";";
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setStyle(normal);
        button.hoverProperty().addListener((o, was, is) -> button.setStyle(is ? hover : normal));
        button.setTooltip(new Tooltip("Clear the filters and what was typed"));
        button.setOnAction(e -> reset(defaults));
        return button;
    }

    /**
     * The page's refresh controls: the "New data" icon ({@link #refreshIcon}) and, always there, the same
     * "↻ Refresh" button as the dashboard's. Refresh loads the list again from the database with the same
     * filters (and hides "New data").
     */
    public javafx.scene.layout.HBox refreshButtons() {
        Button icon = refreshIcon();
        Button refresh = new Button("↻  Refresh");
        Styles.rowButton(refresh, Theme.BORDER, Theme.PRIMARY_TEXT);   // as on the dashboard
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        refresh.setTooltip(new Tooltip("Load the list again (your filters stay)"));
        refresh.setOnAction(e -> {
            show(icon, false);
            reload.run();
        });
        javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(8, icon, refresh);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
    }

    /**
     * The refresh icon: hidden until another register's changes arrive by sync, then shown (blue, "New
     * data"); a click loads the list again from the database with the same filters, and hides it.
     */
    public Button refreshIcon() {
        Button icon = new Button("↻  New data");
        String base = "-fx-background-radius: 999; -fx-font-size: 14px; -fx-font-weight: bold; -fx-pref-height: 36; "
                + "-fx-padding: 0 16 0 14; -fx-cursor: hand; -fx-text-fill: white; -fx-background-color: ";
        String normal = base + Theme.INFORMATION + ";";
        String hover = base + Styles.darker(Theme.INFORMATION) + ";";
        icon.setMinWidth(Region.USE_PREF_SIZE);
        icon.setStyle(normal);
        icon.hoverProperty().addListener((o, was, is) -> icon.setStyle(is ? hover : normal));
        icon.setTooltip(new Tooltip("Another register made changes. Click to load them (your filters stay)."));
        show(icon, false);
        icon.setOnAction(e -> {
            show(icon, false);
            reload.run();
        });
        Runnable listener = () -> Platform.runLater(() -> show(icon, true));
        icon.getProperties().put(LiveFilters.class, listener);   // keeps the (weakly held) listener alive with the icon
        DataChanged.listen(listener);
        return icon;
    }

    private static void show(Button icon, boolean on) {
        icon.setVisible(on);
        icon.setManaged(on);
    }
}
