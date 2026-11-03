package system.ui;

import app.ScreenContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import shared.PhTime;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;
import system.model.StatusItem;
import system.service.SystemException;
import system.service.SystemStatusService;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The "System Status" tab: one card per component (database, other terminal, pending sync, last
 * backup, disk space, log retention) and summary tiles. The checks run off the screen's thread,
 * because reaching another terminal can take a moment. Colour always comes with a word.
 */
final class SystemStatusPane implements SystemPane {

    private static final int COLUMNS = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm:ss", Locale.US);

    private final ScreenContext screen;
    private final SystemStatusService status;

    private final Button refresh = SystemUi.applyButton("Refresh");
    private final Label checked = new Label();
    private final GridPane cards = new GridPane();
    private final Label terminalValue = new Label();
    private final Label okValue = new Label("0");
    private final Label warningValue = new Label("0");
    private final Label problemValue = new Label("0");
    private final Label message = new Label();
    private final VBox view = new VBox(16);

    SystemStatusPane(ScreenContext screen, SystemStatusService status) {
        this.screen = screen;
        this.status = status;

        refresh.setOnAction(e -> load());
        Styles.muted(checked);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, checked, spacer, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        cards.setHgap(16);
        cards.setVgap(16);
        for (int i = 0; i < COLUMNS; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / COLUMNS);
            cards.getColumnConstraints().add(column);
        }

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        Styles.error(message);

        terminalValue.setText(screen.app().config().terminalId());
        HBox tiles = new HBox(18, SystemUi.tile("Terminal", terminalValue), SystemUi.tile("Healthy", okValue),
                SystemUi.tile("Warnings", warningValue), SystemUi.tile("Problems", problemValue));
        tiles.setAlignment(Pos.CENTER);

        Region fill = new Region();
        VBox.setVgrow(fill, Priority.ALWAYS);
        view.getChildren().addAll(toolbar, cards, message, fill, tiles);
    }

    @Override
    public Parent getView() {
        return view;
    }

    /** Called every time the tab is shown. */
    @Override
    public void refresh() {
        if (!refresh.isDisabled()) {   // a check is already running
            load();
        }
    }

    private void load() {
        refresh.setDisable(true);
        checked.setText("Checking...");
        String session = screen.sessionId();
        Thread worker = new Thread(() -> {
            try {
                List<StatusItem> items = status.report(session);
                Platform.runLater(() -> show(items));
            } catch (SystemException e) {
                Platform.runLater(() -> failed(e.getMessage()));
            } catch (RuntimeException e) {
                Platform.runLater(() -> failed(UiErrors.message(e, "The system status could not be checked. Try again.")));
            }
        }, "system-status");
        worker.setDaemon(true);
        worker.start();
    }

    private void failed(String text) {
        refresh.setDisable(false);
        checked.setText("");
        message.setText(text);
    }

    private void show(List<StatusItem> items) {
        refresh.setDisable(false);
        message.setText("");
        checked.setText("Last checked " + TIME.format(PhTime.of(Instant.now())));
        cards.getChildren().clear();
        for (int i = 0; i < items.size(); i++) {
            Node card = card(items.get(i));
            GridPane.setHgrow(card, Priority.ALWAYS);
            cards.add(card, i % COLUMNS, i / COLUMNS);
        }
        okValue.setText(count(items, StatusItem.Level.OK));
        warningValue.setText(count(items, StatusItem.Level.WARNING));
        problemValue.setText(count(items, StatusItem.Level.ERROR));
    }

    private static String count(List<StatusItem> items, StatusItem.Level level) {
        return SystemUi.count(items.stream().filter(i -> i.level() == level).count());
    }

    /** One component: its name and level on top, the short answer in bold, then the sentence. */
    private static Node card(StatusItem item) {
        Label name = new Label(item.name());
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        Label level = new Label(item.level().label());
        Styles.pill(level, colour(item.level()), "white");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, name, spacer, level);
        top.setAlignment(Pos.CENTER_LEFT);

        Label value = new Label(item.value());
        value.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        value.setWrapText(true);
        Label detail = new Label(item.detail() == null ? "" : item.detail());
        detail.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        detail.setWrapText(true);
        detail.setMinHeight(Region.USE_PREF_SIZE);

        VBox card = new VBox(8, top, value, detail);
        Styles.card(card);
        card.setPadding(new Insets(16, 20, 16, 20));
        card.setMinHeight(150);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static String colour(StatusItem.Level level) {
        return switch (level) {
            case OK -> Theme.SUCCESS;
            case INFO -> Theme.INFORMATION;
            case WARNING -> Theme.WARNING;
            case ERROR -> Theme.ERROR;
        };
    }
}
