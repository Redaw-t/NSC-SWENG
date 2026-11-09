package system.ui;

import app.ScreenContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import system.service.LogCleanupService;
import system.service.LogCleanupService.Log;
import system.service.LogCleanupService.Period;
import system.service.SystemException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Clear Logs (Settings &gt; General, Admin only), as a pop-up: tick the logs, choose which entries
 * (older than 3 / 6 months, 1 year, or all time), see how many will go, then Clear Logs, with an "Are you
 * sure?" first. Only logs that are a record of what happened (see LogCleanupService); this register only.
 */
public final class ClearLogsModal {

    private ClearLogsModal() { }

    public static void show(ScreenContext screen, LogCleanupService service, Window owner) {
        Modal modal = new Modal(owner, 680);

        Map<Log, CheckBox> boxes = new EnumMap<>(Log.class);
        Map<Log, Label> counts = new EnumMap<>(Log.class);
        VBox logList = new VBox(14);
        for (Log log : Log.values()) {
            CheckBox box = new CheckBox(log.label());
            Styles.modalCheckBox(box);
            box.setSelected(log == Log.ACTIVITY || log == Log.SYNC);
            Label count = new Label();
            count.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox top = new HBox(12, box, gap, count);
            top.setAlignment(Pos.CENTER_LEFT);
            Label about = new Label(log.about());
            Styles.modalSubtitle(about);
            about.setWrapText(true);
            about.setPadding(new Insets(0, 0, 0, 28));
            logList.getChildren().add(new VBox(4, top, about));
            boxes.put(log, box);
            counts.put(log, count);
        }

        Label which = new Label("Which entries");
        Styles.modalLabel(which);
        ToggleGroup group = new ToggleGroup();
        FlowPane periods = new FlowPane(18, 8);
        for (Period p : Period.values()) {
            RadioButton r = new RadioButton(p.label());
            r.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
            r.setToggleGroup(group);
            r.setUserData(p);
            r.setSelected(p == Period.OLDER_30_DAYS);   // the log retention period (System Status)
            periods.getChildren().add(r);
        }

        Label total = new Label();
        total.setWrapText(true);
        Label copyNote = new Label("Before anything is deleted, a CSV copy of the entries is saved in "
                + service.copyFolder() + ". Keep it safe on another drive (a USB flash drive or an external disk), "
                + "then delete it from this PC to save space.");
        copyNote.setWrapText(true);
        copyNote.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-background-color: "
                + "#EEF4FB; -fx-background-radius: 8; -fx-padding: 10 14 10 14;");
        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);
        error.setManaged(false);
        error.setVisible(false);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button clear = new Button("Clear Logs");
        Styles.modalButton(clear, Theme.ACCENT_RED, "white");

        long[] toClear = {0};
        Runnable recount = () -> {
            Set<Log> chosen = chosen(boxes);
            Period period = (Period) group.getSelectedToggle().getUserData();
            for (Log log : Log.values()) {
                counts.get(log).setText(chosen.contains(log) ? "…" : "");
            }
            try {
                Map<Log, Long> n = service.count(screen.sessionId(), EnumSet.allOf(Log.class), period);
                long sum = 0;
                for (Log log : Log.values()) {
                    long c = n.getOrDefault(log, 0L);
                    counts.get(log).setText(c + (c == 1 ? " entry" : " entries"));
                    counts.get(log).setOpacity(chosen.contains(log) ? 1 : 0.45);
                    if (chosen.contains(log)) {
                        sum += c;
                    }
                }
                toClear[0] = sum;
                total.setText(sum == 0 ? "Nothing to clear with these choices."
                        : sum + (sum == 1 ? " entry" : " entries") + " will be deleted from this register.");
                total.setStyle("-fx-font-size: 15px; -fx-text-fill: "
                        + (sum == 0 ? Theme.SECONDARY_TEXT : Theme.PRIMARY_TEXT) + ";");
                clear.setDisable(sum == 0);
                hide(error);
            } catch (SystemException ex) {
                show(error, ex.getMessage());
                clear.setDisable(true);
            }
            modal.sizeToFit();
        };
        boxes.values().forEach(b -> b.selectedProperty().addListener((o, was, is) -> recount.run()));
        group.selectedToggleProperty().addListener((o, was, is) -> {
            if (is == null) {
                was.setSelected(true);   // always one period chosen
            } else {
                recount.run();
            }
        });

        clear.setOnAction(e -> {
            Set<Log> chosen = chosen(boxes);
            Period period = (Period) group.getSelectedToggle().getUserData();
            if (!Modal.confirm(modal.window(), "Clear " + toClear[0] + (toClear[0] == 1 ? " entry?" : " entries?"),
                    "The chosen log entries (" + period.label().toLowerCase(java.util.Locale.ROOT) + ") will be "
                            + "deleted from this register, after a CSV copy is saved. Who cleared them is kept in "
                            + "the activity log.", "Clear Logs", "Cancel")) {
                return;
            }
            try {
                LogCleanupService.Result result = service.clear(screen.sessionId(), chosen, period);
                long sum = result.total();
                modal.close();
                StringBuilder files = new StringBuilder();
                for (java.nio.file.Path p : result.copies()) {
                    files.append("\n• ").append(p.getFileName());
                }
                Modal.notice(owner, "Logs cleared", sum + (sum == 1 ? " entry was" : " entries were")
                        + " deleted from this register."
                        + (result.copies().isEmpty() ? "" : "\n\nA copy of the deleted entries was saved as CSV in "
                                + service.copyFolder() + ":" + files
                                + "\n\nKeep these files safe on another drive (a USB flash drive or an external disk), "
                                + "then delete them from this PC to save space."));
            } catch (SystemException ex) {
                show(error, ex.getMessage());
                modal.sizeToFit();
            }
        });

        Region line = new Region();
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setStyle("-fx-background-color: " + Theme.BORDER + ";");

        modal.card().getChildren().addAll(Modal.title("Clear Logs"),
                Modal.subtitle("Deletes old entries from these logs, on this register only. Sales, returns, stock, "
                        + "products, users, store details and settings are never touched."),
                logList, line, which, periods, total, copyNote, error, Modal.buttons(cancel, clear));
        recount.run();
        modal.showAndWait();
    }

    private static Set<Log> chosen(Map<Log, CheckBox> boxes) {
        Set<Log> out = EnumSet.noneOf(Log.class);
        boxes.forEach((log, box) -> {
            if (box.isSelected()) {
                out.add(log);
            }
        });
        return out;
    }

    private static void show(Label label, String text) {
        label.setText(text);
        label.setManaged(true);
        label.setVisible(true);
    }

    private static void hide(Label label) {
        label.setManaged(false);
        label.setVisible(false);
    }
}
