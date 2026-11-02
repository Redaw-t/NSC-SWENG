package shared;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.Locale;
import java.util.function.Function;

/**
 * A drop-down for choosing one item. By default it has a search box on top, for long lists
 * such as categories and suppliers: click it (or press Enter / Space / Down), type part of a
 * name, and press Enter to take the first match or click a name. Without the search box
 * ({@code searchable} false) it is a plain list, for short lists such as units. Clicking the
 * drop-down again closes it. Items are shown with their {@code toString()}.
 *
 * <p>It has the small part of the ComboBox API the screens use: {@link #getItems()},
 * {@link #valueProperty()}, {@link #getValue()}, {@link #setValue}, {@link #setPromptText}.
 */
public final class SearchPicker<T> extends HBox {

    private static final double ROW_HEIGHT = 36;
    private static final int MAX_ROWS = 7;
    private static final long REOPEN_GUARD_NANOS = 250_000_000L;

    private final boolean underline;
    private final boolean searchable;
    private final ObservableList<T> items = FXCollections.observableArrayList();
    private final FilteredList<T> matches = new FilteredList<>(items, t -> true);
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final StringProperty promptText = new SimpleStringProperty("");

    private final Label shown = new Label();
    private final TextField search = new TextField();
    private final TextField custom = new TextField();   // the optional typed-in choice (see allowCustom)
    private Function<String, T> customFactory;
    private final ListView<T> list = new ListView<>(matches);
    private final VBox panel = new VBox(8);
    private final Popup popup = new Popup();
    private long closedAt;

    /** A picker with a search box. */
    public SearchPicker(boolean underline) {
        this(underline, true);
    }

    /**
     * @param underline  true for the line style used inside pop-up forms, false for the grey filter box
     * @param searchable false to leave out the search box (short lists)
     */
    public SearchPicker(boolean underline, boolean searchable) {
        this.underline = underline;
        this.searchable = searchable;
        setAlignment(Pos.CENTER_LEFT);
        setFocusTraversable(true);
        Styles.picker(this, underline);

        shown.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(shown, Priority.ALWAYS);
        Label arrow = new Label("\u25BE");
        Styles.pickerArrow(arrow);
        getChildren().addAll(shown, arrow);
        refreshText();
        value.addListener((obs, old, v) -> refreshText());
        promptText.addListener((obs, old, v) -> refreshText());

        search.setPromptText("Search...");
        Styles.input(search);
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPlaceholder(new Label("No match"));
        list.setCellFactory(lv -> new PickerCell());
        Styles.pickerList(list);
        panel.setPadding(new Insets(10));
        if (searchable) {
            panel.getChildren().add(search);
        }
        panel.getChildren().add(list);
        Styles.pickerPopup(panel);
        popup.getContent().add(panel);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setOnHidden(e -> {
            closedAt = System.nanoTime();
            commitCustom();   // text typed in the custom field counts even if the user just clicked away
        });

        search.textProperty().addListener((obs, old, text) -> {
            String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            matches.setPredicate(t -> q.isEmpty() || String.valueOf(t).toLowerCase(Locale.ROOT).contains(q));
            if (!matches.isEmpty()) {
                list.getSelectionModel().selectFirst();
            }
        });
        matches.addListener((ListChangeListener<T>) change -> resizeList());
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                choose(list.getSelectionModel().getSelectedItem());
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                list.requestFocus();
                e.consume();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                choose(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                choose(list.getSelectionModel().getSelectedItem());
            }
        });

        setOnMouseClicked(e -> {
            requestFocus();
            toggle();
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.DOWN) {
                toggle();
                e.consume();
            }
        });
    }

    public ObservableList<T> getItems() {
        return items;
    }

    public ObjectProperty<T> valueProperty() {
        return value;
    }

    public T getValue() {
        return value.get();
    }

    public void setValue(T newValue) {
        value.set(newValue);
    }

    /**
     * Adds a text field at the bottom of the list so the user can type a choice that is not in it
     * (press Enter, or just click away). {@code factory} turns the typed text into an item.
     */
    public void allowCustom(String prompt, Function<String, T> factory) {
        customFactory = factory;
        custom.setPromptText(prompt);
        Styles.underlineFaintPrompt(custom);
        custom.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                commitCustom();
                popup.hide();
                requestFocus();
                e.consume();
            }
        });
        if (!panel.getChildren().contains(custom)) {
            panel.getChildren().add(custom);
        }
    }

    private void commitCustom() {
        if (customFactory == null) {
            return;
        }
        String typed = custom.getText() == null ? "" : custom.getText().trim();
        custom.clear();
        if (!typed.isEmpty()) {
            value.set(customFactory.apply(typed));
        }
    }

    /** The grey text shown while nothing is chosen. */
    public void setPromptText(String text) {
        promptText.set(text == null ? "" : text);
    }

    private void refreshText() {
        T chosen = value.get();
        shown.setText(chosen == null ? promptText.get() : String.valueOf(chosen));
        Styles.pickerText(shown, underline, chosen == null);
    }

    private void choose(T item) {
        if (item == null) {
            return;
        }
        custom.clear();
        value.set(item);
        popup.hide();
        requestFocus();
    }

    /** Opens the list, or closes it if it is already open. */
    private void toggle() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        if (System.nanoTime() - closedAt < REOPEN_GUARD_NANOS) {
            return;   // the same click already closed the list (auto-hide): do not reopen it
        }
        Bounds bounds = localToScreen(getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        search.clear();
        custom.clear();
        list.getSelectionModel().clearSelection();
        panel.setPrefWidth(Math.max(getWidth(), searchable ? 240 : 150));
        resizeList();
        popup.show(this, bounds.getMinX(), bounds.getMaxY() + 2);
        T current = value.get();
        if (current != null) {
            list.getSelectionModel().select(current);
            list.scrollTo(current);
        }
        if (searchable) {
            search.requestFocus();
        } else {
            list.requestFocus();
        }
    }

    private void resizeList() {
        int rows = Math.max(1, Math.min(MAX_ROWS, matches.size()));
        list.setPrefHeight(rows * ROW_HEIGHT + 4);
    }

    /** A list row with a soft blue tint under the mouse or when selected. */
    private final class PickerCell extends ListCell<T> {

        PickerCell() {
            hoverProperty().addListener((obs, was, is) -> paint());
            selectedProperty().addListener((obs, was, is) -> paint());
            paint();
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : String.valueOf(item));
            paint();
        }

        private void paint() {
            double tint = isEmpty() ? 0 : isSelected() ? 0.14 : isHover() ? 0.08 : 0;
            Styles.pickerCell(this, tint);
        }
    }
}
