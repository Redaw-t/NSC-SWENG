package system.ui;

import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import shared.Modal;
import shared.PhTime;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;
import system.model.SyncLogEntry;
import system.model.SyncLogFilter;
import system.service.SyncLogService;
import system.service.SystemException;

import java.io.File;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The "Sync Logs" tab, laid out like the Sales Reports design: every attempt to send a record to
 * another terminal, with a Retry button on failed ones. The tiles show what is waiting, what
 * failed and when the last send worked. Retry only forwards the request to the Sync module.
 */
final class SyncLogPane implements SystemPane {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    /** One choice in the Status drop-down; {@code code} null = every status. */
    private record StatusChoice(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final StatusChoice ALL_STATUSES = new StatusChoice(null, "All Statuses");
    private static final String EMPTY = "No sync attempts recorded for these filters.";

    private final ScreenContext screen;
    private final SyncLogService logs;

    private final DatePicker from = SystemUi.datePicker("From Date", this::load);
    private final DatePicker to = SystemUi.datePicker("To Date", this::load);
    private final SearchPicker<StatusChoice> statusFilter = new SearchPicker<>(false, false);
    private final TextField search = SystemUi.searchField("Search record or terminal...", this::load);
    private final TableView<SyncLogEntry> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label unsentValue = new Label("0");
    private final Label failedValue = new Label("0");
    private final Label syncedValue = new Label("0");
    private final Label lastValue = new Label("Never");
    private final Label notice = new Label();
    private final Label message = new Label();
    private final VBox view = new VBox(16);

    private List<SyncLogEntry> rows = List.of();

    SyncLogPane(ScreenContext screen, SyncLogService logs) {
        this.screen = screen;
        this.logs = logs;

        table.getColumns().add(Tables.<SyncLogEntry>textColumn("Date / Time", 120,
                e -> ROW_TIME.format(PhTime.of(e.createdAt()))));
        table.getColumns().add(Tables.<SyncLogEntry>textColumn("Source", 80, SyncLogEntry::sourceTerminal));
        table.getColumns().add(Tables.<SyncLogEntry>textColumn("Target", 80, SyncLogEntry::targetTerminal));
        table.getColumns().add(Tables.<SyncLogEntry>textColumn("Data Type", 100, SyncLogEntry::dataType));
        table.getColumns().add(Tables.<SyncLogEntry>nodeColumn("Record", 190, 130,
                e -> SystemUi.wrappedCell(e.recordId())));
        table.getColumns().add(Tables.<SyncLogEntry>nodeColumn("Status", 95, 85, SyncLogPane::statusCell));
        table.getColumns().add(Tables.<SyncLogEntry>textColumn("Retries", 75, e -> String.valueOf(e.retryCount())));
        table.getColumns().add(Tables.<SyncLogEntry>nodeColumn("Error", 240, 140,
                e -> SystemUi.wrappedCell(e.errorMessage())));
        table.getColumns().add(Tables.<SyncLogEntry>nodeColumn("Actions", 150, 140, this::actions));
        SystemUi.placeholder(table, EMPTY);
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        statusFilter.getItems().setAll(ALL_STATUSES,
                new StatusChoice(SyncLogEntry.PENDING, "Pending"),
                new StatusChoice(SyncLogEntry.SYNCED, "Synced"),
                new StatusChoice(SyncLogEntry.FAILED, "Failed"));
        statusFilter.setValue(ALL_STATUSES);
        statusFilter.setPrefWidth(170);
        search.setPrefWidth(260);

        Button apply = SystemUi.applyButton("Apply");
        apply.setOnAction(e -> load());
        Button export = SystemUi.exportButton(this::export);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, from, to, statusFilter, search, apply, spacer, export);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        notice.setWrapText(true);
        notice.setMinHeight(Region.USE_PREF_SIZE);
        Styles.muted(notice);
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        view.getChildren().addAll(toolbar, notice, table, message, SystemUi.footer(pager,
                SystemUi.tile("Unsent Sales", unsentValue), SystemUi.tile("Failed", failedValue),
                SystemUi.tile("Synced", syncedValue), SystemUi.tile("Last Sync", lastValue)));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    @Override
    public Parent getView() {
        return view;
    }

    /** Called every time the tab is shown. */
    @Override
    public void refresh() {
        load();
    }

    @Override
    public void focusSearch() {
        search.requestFocus();
    }

    /* --------------------------------- data --------------------------------- */

    private void load() {
        SyncLogFilter filter;
        try {
            StatusChoice status = statusFilter.getValue();
            filter = new SyncLogFilter(from.getValue(), to.getValue(), status == null ? null : status.code(),
                    search.getText());
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        message.setText("");
        try {
            rows = logs.find(screen.sessionId(), filter);
            SystemUi.placeholder(table, EMPTY);
            int unsent = logs.unsyncedSales(screen.sessionId());
            Optional<Instant> last = logs.lastSuccess(screen.sessionId());
            unsentValue.setText(SystemUi.count(unsent));
            lastValue.setText(last.map(at -> ROW_TIME.format(PhTime.of(at))).orElse("Never"));
            boolean connected = logs.syncConnected();
            notice.setVisible(!connected);
            notice.setManaged(!connected);
            notice.setText("Synchronization is not connected on this terminal yet. Completed sales are stored "
                    + "locally and will be sent once it is connected.");
        } catch (SystemException e) {
            loadFailed(e.getMessage());
        } catch (RuntimeException e) {
            loadFailed(UiErrors.message(e, "The sync log could not be loaded. Try again."));
        }
        failedValue.setText(SystemUi.count(rows.stream().filter(e -> SyncLogEntry.FAILED.equals(e.status())).count()));
        syncedValue.setText(SystemUi.count(rows.stream().filter(e -> SyncLogEntry.SYNCED.equals(e.status())).count()));
        pager.reset();
        showPage();
    }

    private void loadFailed(String text) {
        rows = List.of();
        unsentValue.setText("-");   // not 0: the number is unknown
        lastValue.setText("-");
        SystemUi.placeholder(table, SystemUi.NOT_LOADED);
        showError(text);
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    /* -------------------------------- actions -------------------------------- */

    private Node actions(SyncLogEntry e) {
        HBox box = new HBox(8, SystemUi.viewButton(() -> showDetails(e)));
        box.setAlignment(Pos.CENTER);
        if (SyncLogEntry.FAILED.equals(e.status())) {
            Button retry = new Button("Retry");
            Styles.rowButton(retry, Theme.INFORMATION, "white");
            retry.setOnAction(ev -> retry(e));
            box.getChildren().add(retry);
        }
        return box;
    }

    private void retry(SyncLogEntry e) {
        try {
            logs.retry(screen.sessionId(), e);
            Modal.notice(screen.stage(), "Retry requested", "The record will be sent again.");
            load();
        } catch (SystemException ex) {
            showError(ex.getMessage());
        }
    }

    private void showDetails(SyncLogEntry e) {
        SystemUi.showDetails(screen.stage(), "Sync Details", List.of(
                new String[] {"Date / Time", FULL_TIME.format(PhTime.of(e.createdAt()))},
                new String[] {"Last Update", FULL_TIME.format(PhTime.of(e.updatedAt()))},
                new String[] {"Source", e.sourceTerminal()},
                new String[] {"Target", e.targetTerminal()},
                new String[] {"Data Type", e.dataType()},
                new String[] {"Record", e.recordId()},
                new String[] {"Status", e.statusLabel()},
                new String[] {"Retries", String.valueOf(e.retryCount())},
                new String[] {"Error", e.errorMessage()}));
    }

    @Override
    public void export() {
        if (rows.isEmpty()) {
            Modal.notice(screen.stage(), "Nothing to export", "There are no sync attempts for these filters.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export sync log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV file (*.csv)", "*.csv"));
        chooser.setInitialFileName("sync-log-" + FILE_DATE.format(PhTime.today()) + ".csv");
        File file = chooser.showSaveDialog(screen.stage());
        if (file == null) {
            return;   // cancelled
        }
        try {
            int written = logs.exportCsv(screen.sessionId(), rows, file.toPath());
            Modal.notice(screen.stage(), "Export complete",
                    SystemUi.count(written) + " sync attempts were saved to " + file.getName() + ".");
        } catch (SystemException e) {
            showError(e.getMessage());
        }
    }

    /* -------------------------------- helpers -------------------------------- */

    /** The status in words and colour (never colour alone). */
    private static Node statusCell(SyncLogEntry e) {
        String colour = switch (e.status()) {
            case SyncLogEntry.SYNCED -> Theme.SUCCESS;
            case SyncLogEntry.FAILED -> Theme.ERROR;
            default -> Theme.WARNING;
        };
        return SystemUi.colouredCell(e.statusLabel(), colour);
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }
}
