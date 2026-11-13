package system.ui;

import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
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
import shared.Toast;

import system.model.ActivityDescription;
import system.model.ActivityEntry;
import system.model.ActivityFilter;
import system.model.AuditEvent;
import system.service.ActivityLogService;
import system.service.SystemException;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The "Activity Logs" tab, laid out like the Sales Reports design: From / To dates, User and
 * Module filters, a search box and Apply on the left, Export CSV on the right; the log table with a
 * View button on every row; and "Showing 1-20 of N", the summary tiles and the page links below.
 *
 * Read-only: nothing here changes a log entry. Every load goes through
 * {@link ActivityLogService}, which checks the permission again.
 */
final class ActivityLogPane implements SystemPane {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final DateTimeFormatter FULL_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    private static final String EMPTY = "No activity found for these filters.";

    private static final Set<String> SIGN_INS = Set.of("LOGIN_SUCCESS");
    private static final Set<String> CHANGE_MODULES = Set.of(AuditEvent.USERS, AuditEvent.PRODUCTS,
            AuditEvent.INVENTORY);

    private boolean updating;   // true while Reset or the User list changes the filters itself


    /** One choice in the User drop-down; {@code id} null = everyone. */
    private record UserChoice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** One choice in the Module drop-down; {@code module} null = every module. */
    private record ModuleChoice(String module, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final UserChoice ALL_USERS = new UserChoice(null, "All Users");
    private static final ModuleChoice ALL_MODULES = new ModuleChoice(null, "All Modules");

    private final ScreenContext screen;
    private final ActivityLogService activity;

    private final DatePicker from = SystemUi.datePicker("From Date", this::load);
    private final DatePicker to = SystemUi.datePicker("To Date", this::load);
    private final SearchPicker<UserChoice> userFilter = new SearchPicker<>(false);
    private final SearchPicker<ModuleChoice> moduleFilter = new SearchPicker<>(false, false);
    private final TextField search = SystemUi.searchField("Search logs...", this::load);
    private final TableView<ActivityEntry> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label totalValue = new Label("0");
    private final Label signInValue = new Label("0");
    private final Label failedValue = new Label("0");
    private final Label changeValue = new Label("0");
    private final Label message = new Label();
    private final VBox view = new VBox(16);
    private final UserNames names;

    private List<ActivityEntry> rows = List.of();

    ActivityLogPane(ScreenContext screen, ActivityLogService activity) {
        this.screen = screen;
        this.activity = activity;
        this.names = new UserNames(screen);

        table.getColumns().add(Tables.<ActivityEntry>textColumn("Date / Time", 120,
                e -> ROW_TIME.format(PhTime.of(e.createdAt()))));
        table.getColumns().add(Tables.<ActivityEntry>textColumn("User", 150,
                e -> unknownUser(e) ? "Unregistered user" : names.name(e.userId())));
        TableColumn<ActivityEntry, String> terminal = Tables.textColumn("Terminal", 85, ActivityEntry::terminalId);
        terminal.setMinWidth(85);   // the header never shrinks to "Termi..."
        table.getColumns().add(terminal);
        table.getColumns().add(Tables.<ActivityEntry>textColumn("Module", 95,
                e -> AuditEvent.moduleLabel(e.module())));
        table.getColumns().add(Tables.<ActivityEntry>nodeColumn("Action", 190, 150, ActivityLogPane::actionCell));
        table.getColumns().add(Tables.<ActivityEntry>nodeColumn("Target", 130, 100,
                e -> SystemUi.wrappedCell(e.target())));
        table.getColumns().add(Tables.<ActivityEntry>nodeColumn("Details", 320, 200,
                e -> SystemUi.wrappedCell(ActivityDescription.describe(e))));
        table.getColumns().add(Tables.<ActivityEntry>nodeColumn("Actions", 100, 90,
                e -> SystemUi.viewButton(() -> showDetails(e))));
        SystemUi.placeholder(table, EMPTY);
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        userFilter.setValue(ALL_USERS);
        userFilter.setPrefWidth(190);
        moduleFilter.getItems().add(ALL_MODULES);
        AuditEvent.MODULES.forEach(m -> moduleFilter.getItems().add(new ModuleChoice(m, AuditEvent.moduleLabel(m))));
        moduleFilter.setValue(ALL_MODULES);
        moduleFilter.setPrefWidth(170);

        Runnable onFilter = () -> {
            if (!updating) {
                load();
            }
        };
        from.valueProperty().addListener((o, was, is) -> onFilter.run());
        to.valueProperty().addListener((o, was, is) -> onFilter.run());
        userFilter.valueProperty().addListener((o, was, is) -> onFilter.run());
        moduleFilter.valueProperty().addListener((o, was, is) -> onFilter.run());
        SystemUi.onTyping(search, onFilter);
        Button reset = SystemUi.resetButton(this::reset);
        Button export = SystemUi.exportButton(this::export);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, from, to, userFilter, moduleFilter, search, reset, spacer, export);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);

        view.getChildren().addAll(toolbar, table, message, SystemUi.footer(pager,
                SystemUi.tile("Total Entries", totalValue), SystemUi.tile("Sign-ins", signInValue),
                SystemUi.tile("Failed Sign-ins", failedValue), SystemUi.tile("Changes", changeValue)));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    @Override
    public Parent getView() {
        return view;
    }

    /** Called every time the tab is shown: reloads the User choices and the log. */
    @Override
    public void refresh() {
        loadUserChoices();
        load();
    }

    @Override
    public void focusSearch() {
        search.requestFocus();
    }

    /* --------------------------------- data --------------------------------- */

    private void load() {
        ActivityFilter filter;
        try {
            UserChoice user = userFilter.getValue();
            ModuleChoice module = moduleFilter.getValue();
            filter = new ActivityFilter(from.getValue(), to.getValue(), user == null ? null : user.id(),
                    module == null ? null : module.module(), search.getText());
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        try {
            rows = activity.find(screen.sessionId(), filter);
            rows.stream().map(ActivityEntry::userId).distinct().forEach(names::remember);
            SystemUi.placeholder(table, EMPTY);
            if (rows.size() >= ActivityLogService.MAX_ROWS) {
                showInfo("Showing the newest " + SystemUi.count(ActivityLogService.MAX_ROWS) + " entries. "
                        + "Choose a shorter date range to see older ones.");
            } else {
                message.setText("");
            }
        } catch (SystemException e) {
            loadFailed(e.getMessage());
        } catch (RuntimeException e) {
            loadFailed(UiErrors.message(e, "The activity log could not be loaded. Try again."));
        }
        updateTiles();
        pager.reset();
        showPage();
    }

    private void loadFailed(String text) {
        rows = List.of();
        SystemUi.placeholder(table, SystemUi.NOT_LOADED);
        showError(text);
    }

    private void loadUserChoices() {
        List<UserChoice> choices = new ArrayList<>();
        choices.add(ALL_USERS);
        try {
            List<String> ids = activity.userIds(screen.sessionId());
            ids.forEach(names::remember);
            ids.forEach(id -> choices.add(new UserChoice(id, names.label(id))));
        } catch (SystemException | RuntimeException e) {
            // the list stays "All Users"; load() shows the problem
        }
        UserChoice current = userFilter.getValue();

        updating = true;
        try {
            userFilter.getItems().setAll(choices);
            userFilter.setValue(current != null && choices.contains(current) ? current : ALL_USERS);
        } finally {
            updating = false;
        }
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    private void updateTiles() {
        totalValue.setText(SystemUi.count(rows.size()));
        signInValue.setText(count(e -> SIGN_INS.contains(e.action())));
        failedValue.setText(count(e -> AuditEvent.isLoginProblem(e.action())));
        changeValue.setText(count(e -> CHANGE_MODULES.contains(e.module())));
    }

    private String count(Predicate<ActivityEntry> test) {
        return SystemUi.count(rows.stream().filter(test).count());
    }

    /* -------------------------------- actions -------------------------------- */

    /** The "Activity Details" pop-up, laid out like the design's Sale Details. */
    private void showDetails(ActivityEntry e) {
        SystemUi.showDetails(screen.stage(), "Activity Details", List.of(
                new String[] {"Date / Time", FULL_TIME.format(PhTime.of(e.createdAt()))},
                new String[] {"User", unknownUser(e) ? "Unregistered username (no matching account)"
                        : names.label(e.userId())},
                new String[] {"Terminal", e.terminalId()},
                new String[] {"Module", AuditEvent.moduleLabel(e.module())},
                new String[] {"Action", AuditEvent.readable(e.action()) + "  (" + e.action() + ")"},
                new String[] {unknownUser(e) ? "Username typed" : "Target", e.target()},
                new String[] {"Old Value", e.oldValue()},
                new String[] {"New Value", e.newValue()},
                new String[] {"Details", ActivityDescription.describe(e)}));
    }

    @Override
    public void export() {
        if (rows.isEmpty()) {
            Modal.notice(screen.stage(), "Nothing to export", "There are no log entries for these filters.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export activity log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV file (*.csv)", "*.csv"));
        chooser.setInitialFileName("activity-log-" + FILE_DATE.format(PhTime.today()) + ".csv");
        File file = chooser.showSaveDialog(screen.stage());
        if (file == null) {
            return;   // cancelled
        }
        try {
            int written = activity.exportCsv(screen.sessionId(), rows, file.toPath(), names::nameOrNull);
            Toast.success(screen.stage(), "Export complete",
                    SystemUi.count(written) + " rows were saved to " + file.getName() + ".");
        } catch (SystemException e) {
            showError(e.getMessage());
            Toast.error(screen.stage(), "Export failed", e.getMessage());
        }
    }

    /* -------------------------------- helpers -------------------------------- */

    /** A login attempt with a username no account has (the typed name is the target). */
    private static boolean unknownUser(ActivityEntry e) {
        return AuditEvent.UNKNOWN_USER.equals(e.action());
    }

    /** The Action cell: plain text, or bold red for a failed or blocked login so it stands out. */
    private static Node actionCell(ActivityEntry e) {
        Label label = new Label(AuditEvent.readable(e.action()));
        label.setStyle(AuditEvent.isLoginProblem(e.action())
                ? "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + Theme.ERROR + ";"
                : "-fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return label;
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }

    private void showInfo(String text) {
        message.setText(text);
        Styles.muted(message);
    }

    private void reset() {
        updating = true;
        from.setValue(null);
        to.setValue(null);
        userFilter.setValue(ALL_USERS);
        moduleFilter.setValue(ALL_MODULES);
        search.clear();
        updating = false;
        load();
    }


}
