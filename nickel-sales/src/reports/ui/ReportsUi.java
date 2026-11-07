package reports.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import shared.Money;
import shared.Styles;
import shared.TablePager;
import shared.Theme;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The pieces of the Reports design the report tabs share: grey date boxes and search box, the
 * light-blue Apply button, table placeholders, the blue summary tiles and the table footer.
 * (The same look as the System module's tabs; copied, because one module may not import
 * another module's ui package.)
 */
final class ReportsUi {

    /** What an empty table says when its rows could not be loaded (the reason is in the message below it). */
    static final String NOT_LOADED = "The report could not be loaded.";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);

    private ReportsUi() { }

    /** One choice in a drop-down: the value, and the words shown for it. */
    record Choice<T>(T value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** "₱1,234.50" */
    static String peso(BigDecimal amount) {
        return "₱" + Money.format(amount);
    }

    /** {@code n} with thousands separators. */
    static String count(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /**
     * A grey date box: a click opens the calendar (the date can't be typed); Delete clears it.
     * Enter runs {@code onEnter}.
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
        picker.skinProperty().addListener((obs, old, skin) -> picker.lookupAll(".arrow-button").forEach(n ->
                n.setStyle("-fx-background-color: transparent; -fx-cursor: hand;")));
        picker.getEditor().setStyle("-fx-background-color: transparent; -fx-font-size: 14px; -fx-text-fill: "
                + Theme.PRIMARY_TEXT + "; -fx-prompt-text-fill: " + Theme.SECONDARY_TEXT + ";");
        Styles.calendarPopup(picker);   // rounded calendar, like the drop-down panels
        Styles.pickOnly(picker);        // no typing: a click opens the calendar
        return picker;
    }

    /** The light-blue Apply button. */
    static Button applyButton(Runnable onClick) {
        Button button = new Button("Apply");
        String base = "-fx-background-radius: 4; -fx-font-size: 15px; -fx-pref-height: 40; -fx-padding: 0 24 0 24; "
                + "-fx-cursor: hand; -fx-text-fill: white; -fx-background-color: ";
        String normal = base + Theme.INFORMATION + ";";
        String hover = base + Styles.darker(Theme.INFORMATION) + ";";
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setStyle(normal);
        button.hoverProperty().addListener((obs, was, is) -> button.setStyle(is ? hover : normal));
        button.setOnAction(e -> onClick.run());
        return button;
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

    /** What {@code table} says while it has no rows. */
    static void placeholder(TableView<?> table, String text) {
        Label label = new Label(text);
        Styles.muted(label);
        table.setPlaceholder(label);
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

    /** The footer under a table: "Showing ..." and the page links, with the tiles centred between them. */
    static StackPane footer(TablePager pager, Node... tiles) {
        HBox row = new HBox(18, tiles);
        row.setAlignment(Pos.CENTER);
        row.setPickOnBounds(false);   // clicks between the tiles reach the page links
        StackPane footer = new StackPane(pager.getView(), row);
        footer.setPadding(new Insets(4, 0, 0, 0));
        return footer;
    }

    /** A message line under the toolbar: red for a problem, grey for a note. */
    static Label messageLabel() {
        Label message = new Label();
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        return message;
    }
}
