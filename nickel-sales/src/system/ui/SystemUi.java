package system.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import shared.Modal;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * The pieces of the Reports design the System tabs share: the grey date boxes and search box,
 * the light-blue Apply button, the grey Export button, the table cells, the details pop-up and
 * the blue summary tiles under the table.
 */
final class SystemUi {

    /** What an empty table says when its rows could not be loaded (the reason is in the message below it). */
    static final String NOT_LOADED = "The list could not be loaded.";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);

    private SystemUi() { }

    /**
     * A grey "From Date" / "To Date" box: a click opens the calendar (the date can't be typed);
     * Delete clears it. Enter runs {@code onEnter}.
     */
    static DatePicker datePicker(String prompt, Runnable onEnter) {
        DatePicker picker = new DatePicker();
        picker.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                picker.setValue(picker.getConverter().fromString(picker.getEditor().getText()));
                onEnter.run();
            }
        });
        picker.setPromptText(prompt);
        picker.setPrefWidth(150);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : DATE.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return LocalDate.parse(text.trim(), DATE);
                } catch (DateTimeParseException e) {
                    return picker.getValue();   // keep the last good date
                }
            }
        });
        String box = "-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 4; -fx-pref-height: 40; "
                + "-fx-font-size: 14px;";
        picker.setStyle(box + " -fx-border-color: transparent; -fx-border-radius: 4;");
        picker.focusedProperty().addListener((obs, was, is) -> picker.setStyle(box + " -fx-border-radius: 4; "
                + "-fx-border-color: " + (is ? Theme.PRIMARY_BLUE : "transparent") + ";"));
        // The calendar button is drawn flat on the grey box (it exists once the picker has its skin).
        picker.skinProperty().addListener((obs, old, skin) -> picker.lookupAll(".arrow-button").forEach(n ->
                n.setStyle("-fx-background-color: transparent; -fx-cursor: hand;")));
        picker.getEditor().setStyle("-fx-background-color: transparent; -fx-font-size: 14px; -fx-text-fill: "
                + Theme.PRIMARY_TEXT + "; -fx-prompt-text-fill: " + Theme.SECONDARY_TEXT + ";");
        Styles.calendarPopup(picker);   // rounded calendar, like the drop-down panels
        Styles.pickOnly(picker);        // no typing: a click opens the calendar
        return picker;
    }

    /** The light-blue Apply button. */
    static Button applyButton(String text) {
        Button button = new Button(text);
        styleButton(button, Theme.INFORMATION, "white");
        return button;
    }

    /** The grey Export CSV / Print Report button. */
    static Button secondaryButton(String text) {
        Button button = new Button(text);
        styleButton(button, Theme.BORDER, Theme.PRIMARY_TEXT);
        return button;
    }

    /** The grey Export CSV button, with its shortcut on hover. */
    static Button exportButton(Runnable onClick) {
        Button button = secondaryButton("Export CSV");
        button.setTooltip(new Tooltip("Ctrl+E"));
        button.setOnAction(e -> onClick.run());
        return button;
    }

    /** What {@code table} says while it has no rows. */
    static void placeholder(TableView<?> table, String text) {
        Label label = new Label(text);
        Styles.muted(label);
        table.setPlaceholder(label);
    }

    private static void styleButton(Button button, String background, String textColour) {
        String base = "-fx-background-radius: 4; -fx-font-size: 15px; -fx-pref-height: 40; -fx-padding: 0 24 0 24; "
                + "-fx-cursor: hand; -fx-text-fill: " + textColour + "; -fx-background-color: ";
        String normal = base + background + ";";
        String hover = base + Styles.darker(background) + ";";
        button.setMinWidth(Region.USE_PREF_SIZE);   // the filters shrink first; the button text is never cut
        button.setStyle(normal);
        button.hoverProperty().addListener((obs, was, is) -> button.setStyle(is ? hover : normal));
    }

    /** The footer under a table: "Showing ..." and the page links (the pager) with the tiles centred between them. */
    static StackPane footer(TablePager pager, Node... tiles) {
        HBox row = new HBox(18, tiles);
        row.setAlignment(Pos.CENTER);
        row.setPickOnBounds(false);   // clicks between the tiles reach the page links
        StackPane footer = new StackPane(pager.getView(), row);
        footer.setPadding(new Insets(4, 0, 0, 0));
        return footer;
    }

    /** The grey search box; Enter runs {@code onEnter}. */
    static TextField searchField(String prompt, Runnable onEnter) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setPrefWidth(200);
        field.setMinWidth(110);
        Styles.input(field);
        field.setOnAction(e -> onEnter.run());
        return field;
    }

    /** The grey "View" button of a table row. */
    static Node viewButton(Runnable onClick) {
        Button view = new Button("View");
        Styles.rowButton(view, Theme.BORDER, Theme.PRIMARY_TEXT);
        view.setOnAction(e -> onClick.run());
        HBox box = new HBox(view);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** A table cell that wraps onto a second line, with the full text on hover. */
    static Node wrappedCell(String text) {
        Label label = new Label(text == null ? "" : text);
        label.setWrapText(true);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setAlignment(Pos.CENTER);
        label.setMaxHeight(Tables.ROW_HEIGHT - 8);
        label.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        if (text != null && !text.isEmpty()) {
            Tooltip tip = new Tooltip(text);
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            tip.setShowDelay(Duration.millis(300));
            label.setTooltip(tip);
        }
        return label;
    }

    /** Text in a status colour, bold (a failed sign-in, a failed sync ...). */
    static Node colouredCell(String text, String colour) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + colour + ";");
        return label;
    }

    /**
     * A read-only details pop-up laid out like the design's Sale Details: the title, a rule, then
     * "name   value" rows (rows without a value are left out) and a Close button.
     */
    static void showDetails(Window owner, String title, List<String[]> rows) {
        Modal modal = new Modal(owner, 620);

        Region rule = new Region();
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        rule.setStyle("-fx-background-color: " + Theme.BORDER + ";");

        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(8);
        int r = 0;
        for (String[] row : rows) {
            if (row[1] == null || row[1].isBlank()) {
                continue;
            }
            Label key = new Label(row[0]);
            key.setStyle("-fx-font-size: 17px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
            key.setMinWidth(130);
            Label value = new Label(row[1]);
            value.setStyle("-fx-font-size: 17px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
            value.setWrapText(true);
            grid.add(key, 0, r);
            grid.add(value, 1, r);
            r++;
        }

        Button close = new Button("Close");
        Styles.modalButton(close, Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(e -> modal.close());

        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        modal.card().getChildren().addAll(heading, rule, grid, Modal.buttons(close));
        modal.showAndWait();
    }

    /** {@code n} with thousands separators. */
    static String count(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** A blue summary tile: the name above, the number in bold below. */
    static VBox tile(String name, Label value) {
        Label label = new Label(name);
        label.setStyle("-fx-font-size: 15px; -fx-text-fill: white;");
        value.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");
        VBox tile = new VBox(0, label, value);
        tile.setAlignment(Pos.CENTER);
        tile.setMinWidth(150);
        tile.setStyle("-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-padding: 4 28 4 28;");
        return tile;
    }

    /** The grey Reset button: clears the filters only. */
    static Button resetButton(Runnable onClick) {
        Button button = secondaryButton("Reset");
        button.setOnAction(e -> onClick.run());
        return button;
    }

    /** Runs {@code action} 300 ms after the user stops typing in {@code field}. */
    static void onTyping(TextField field, Runnable action) {
        PauseTransition wait = new PauseTransition(Duration.millis(300));
        wait.setOnFinished(e -> action.run());
        field.textProperty().addListener((o, was, is) -> wait.playFromStart());
    }
}
