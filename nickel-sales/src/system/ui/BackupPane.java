package system.ui;

import app.ScreenContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import shared.Modal;
import shared.PhTime;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;
import system.model.BackupRecord;
import system.repository.DatabaseDump;
import system.service.BackupService;
import system.service.SystemException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The "Backup &amp; Restore" tab, laid out like the Sales Reports design: the backup folder and the
 * Back Up Now / Restore buttons above the history table, with summary tiles below.
 *
 * Backups and restores run off the screen's thread, so the window stays responsive; the buttons
 * are disabled meanwhile. A restore locks the whole window while it runs, because nothing else
 * may use tables that are being replaced, and signs the admin out when it finishes, because the
 * restored users and roles may differ from the current ones.
 */
final class BackupPane implements SystemPane {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US);

    private static final String EMPTY = "No backups have been made yet.";

    private final ScreenContext screen;
    private final BackupService backups;

    private final Label folderValue = new Label();
    private final Button change = SystemUi.secondaryButton("Change Folder");
    private final Button backupNow = SystemUi.applyButton("Back Up Now");
    private final Button restore = SystemUi.secondaryButton("Restore from Backup");
    private final TableView<BackupRecord> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label lastValue = new Label("None");
    private final Label okValue = new Label("0");
    private final Label failedValue = new Label("0");
    private final Label restoreValue = new Label("0");
    private final Label message = new Label();
    private final VBox view = new VBox(16);
    private final UserNames names;

    private List<BackupRecord> rows = List.of();
    private Parent lockedWindow;   // the window's content while a restore runs

    BackupPane(ScreenContext screen, BackupService backups) {
        this.screen = screen;
        this.backups = backups;
        this.names = new UserNames(screen);

        table.getColumns().add(Tables.<BackupRecord>textColumn("Date / Time", 120,
                r -> ROW_TIME.format(PhTime.of(r.createdAt()))));
        table.getColumns().add(Tables.<BackupRecord>textColumn("Type", 120, BackupRecord::kindLabel));
        table.getColumns().add(Tables.<BackupRecord>nodeColumn("File", 320, 180, r -> SystemUi.wrappedCell(r.fileName())));
        table.getColumns().add(Tables.<BackupRecord>textColumn("Size", 80, BackupRecord::sizeLabel));
        table.getColumns().add(Tables.<BackupRecord>textColumn("Terminal", 85, BackupRecord::terminalId));
        table.getColumns().add(Tables.<BackupRecord>nodeColumn("Result", 95, 85, BackupPane::resultCell));
        table.getColumns().add(Tables.<BackupRecord>textColumn("Performed By", 140, r -> names.name(r.userId())));
        table.getColumns().add(Tables.<BackupRecord>nodeColumn("Actions", 90, 80,
                r -> SystemUi.viewButton(() -> showDetails(r))));
        SystemUi.placeholder(table, EMPTY);
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        Label folderName = new Label("Backup folder:");
        folderName.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        folderName.setMinWidth(Region.USE_PREF_SIZE);
        folderValue.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        change.setOnAction(e -> changeFolder());
        backupNow.setOnAction(e -> backUp());
        restore.setOnAction(e -> restore());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, folderName, folderValue, change, spacer, backupNow, restore);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0));

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        view.getChildren().addAll(toolbar, table, message, SystemUi.footer(pager,
                SystemUi.tile("Last Backup", lastValue), SystemUi.tile("Successful", okValue),
                SystemUi.tile("Failed", failedValue), SystemUi.tile("Restores", restoreValue)));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    @Override
    public Parent getView() {
        return view;
    }

    /** Called every time the tab is shown. */
    @Override
    public void refresh() {
        if (!backupNow.isDisabled()) {   // otherwise "Creating the backup..." stays on show
            message.setText("");
        }
        load();
    }

    /* --------------------------------- data --------------------------------- */

    private void load() {
        folderValue.setText(backups.folder().toString());
        try {
            rows = backups.history(screen.sessionId());
            rows.stream().map(BackupRecord::userId).distinct().forEach(names::remember);
            SystemUi.placeholder(table, EMPTY);
        } catch (SystemException e) {
            loadFailed(e.getMessage());
        } catch (RuntimeException e) {
            loadFailed(UiErrors.message(e, "The backup history could not be loaded. Try again."));
        }
        Optional<Instant> last = rows.stream()
                .filter(r -> BackupRecord.BACKUP.equals(r.kind()) && r.succeeded())
                .map(BackupRecord::createdAt).findFirst();
        lastValue.setText(last.map(at -> ROW_TIME.format(PhTime.of(at))).orElse("None"));
        okValue.setText(SystemUi.count(rows.stream().filter(r -> !BackupRecord.RESTORE.equals(r.kind()) && r.succeeded()).count()));
        failedValue.setText(SystemUi.count(rows.stream().filter(r -> !r.succeeded()).count()));
        restoreValue.setText(SystemUi.count(rows.stream().filter(r -> BackupRecord.RESTORE.equals(r.kind()) && r.succeeded()).count()));
        pager.reset();
        showPage();
    }

    private void loadFailed(String text) {
        rows = List.of();
        SystemUi.placeholder(table, SystemUi.NOT_LOADED);
        showError(text);
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    /* -------------------------------- actions -------------------------------- */

    private void changeFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose the backup folder");
        Path current = backups.folder();
        if (Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        File folder = chooser.showDialog(screen.stage());
        if (folder == null) {
            return;   // cancelled
        }
        try {
            backups.changeFolder(screen.sessionId(), folder.toPath());
            showInfo("Backups will now be saved to " + folder + ".");
            load();
        } catch (SystemException e) {
            showError(e.getMessage());
        }
    }

    private void backUp() {
        run("Creating the backup...", () -> {
            BackupRecord record = backups.backup(screen.sessionId());
            return () -> {
                load();
                showInfo("Backup completed: " + record.fileName() + " (" + record.sizeLabel() + ").");
                Modal.notice(screen.stage(), "Backup complete", "The backup was saved as " + record.fileName() + ".");
            };
        });
    }

    private void restore() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a backup to restore");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Nickel Sales backup (*.sql.gz)", "*.gz"));
        if (Files.isDirectory(backups.folder())) {
            chooser.setInitialDirectory(backups.folder().toFile());
        }
        File file = chooser.showOpenDialog(screen.stage());
        if (file == null) {
            return;   // cancelled
        }
        // Reading a big backup to the end takes a while, so this runs off the screen's thread too.
        run("Checking the backup file...", () -> {
            try {
                DatabaseDump.Summary summary = backups.check(screen.sessionId(), file.toPath());
                return () -> confirmRestore(file, summary);
            } catch (SystemException e) {
                return () -> {
                    message.setText("");
                    Modal.notice(screen.stage(), "Backup cannot be used", e.getMessage());
                };
            }
        });
    }

    private void confirmRestore(File file, DatabaseDump.Summary summary) {
        message.setText("");
        String created = FULL_TIME.format(PhTime.of(Instant.parse(summary.created())));
        boolean confirmed = Modal.confirm(screen.stage(), "Restore this backup?",
                "This replaces ALL current data on this terminal with the backup from " + created + " (terminal "
                        + summary.terminal() + "). Anything recorded since then will be removed.\n\n"
                        + "A safety backup of the current data is made first. Make sure no one is using the other "
                        + "terminal. You will be signed out when it finishes.",
                "Restore");
        if (!confirmed) {
            return;
        }
        lockedWindow = view.getScene().getRoot();
        lockedWindow.setDisable(true);
        run("Restoring the backup. Do not close the application...", () -> {
            backups.restore(screen.sessionId(), file.toPath());
            return () -> {
                Modal.notice(screen.stage(), "Restore complete",
                        "The data was restored. Please sign in again.");
                try {
                    screen.app().auth().logout(screen.sessionId());
                } finally {
                    screen.goHome();   // the session has ended, so the router shows the login screen
                }
            };
        });
    }

    /** Work that runs in the background and returns what to do on the screen afterwards. */
    private interface Job {
        Runnable run() throws SystemException;
    }

    private void run(String progress, Job job) {
        setBusy(true);
        showInfo(progress);
        Thread worker = new Thread(() -> {
            try {
                Runnable done = job.run();
                Platform.runLater(() -> {
                    setBusy(false);
                    done.run();
                });
            } catch (SystemException e) {
                Platform.runLater(() -> {
                    setBusy(false);
                    load();
                    showError(e.getMessage());
                    Modal.notice(screen.stage(), "Could not complete", e.getMessage());
                });
            } catch (RuntimeException | Error e) {
                Platform.runLater(() -> {
                    setBusy(false);
                    String text = UiErrors.message(e, "Something went wrong. Try again.");
                    showError(text);
                    Modal.notice(screen.stage(), "Could not complete", text);
                });
            }
        }, "backup-restore");
        worker.setDaemon(false);   // closing the window must not cut a restore off half-way
        worker.start();
    }

    private void setBusy(boolean busy) {
        change.setDisable(busy);
        backupNow.setDisable(busy);
        restore.setDisable(busy);
        if (!busy && lockedWindow != null) {
            lockedWindow.setDisable(false);
            lockedWindow = null;
        }
    }

    private void showDetails(BackupRecord r) {
        SystemUi.showDetails(screen.stage(), "Backup Details", List.of(
                new String[] {"Date / Time", FULL_TIME.format(PhTime.of(r.createdAt()))},
                new String[] {"Type", r.kindLabel()},
                new String[] {"File", r.fileName()},
                new String[] {"Folder", r.folderPath()},
                new String[] {"Size", r.sizeLabel()},
                new String[] {"Terminal", r.terminalId()},
                new String[] {"Result", r.resultLabel()},
                new String[] {"Message", r.message()},
                new String[] {"Performed By", names.name(r.userId())}));
    }

    /* -------------------------------- helpers -------------------------------- */

    private static Node resultCell(BackupRecord r) {
        return SystemUi.colouredCell(r.resultLabel(), r.succeeded() ? Theme.SUCCESS : Theme.ERROR);
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }

    private void showInfo(String text) {
        message.setText(text);
        Styles.muted(message);
    }
}
