package system.ui;

import app.ScreenContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import pos.receipt.StoreInfo;
import shared.Modal;
import shared.PhTime;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;
import system.model.StoreDetailsRecord;
import system.service.StoreDetailsService;
import system.service.SystemException;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The "Store Details" tab of Settings (Admin only): the details printed at the top of every receipt,
 * which an admin can change, and below them the log of every change with who performed it. A save
 * never edits an old row: it adds a new one, and the newest row is the one receipts use.
 */
final class StoreDetailsPane implements SystemPane {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final String EMPTY = "No changes yet. Receipts use the details above until one is saved.";

    private final ScreenContext screen;
    private final StoreDetailsService service;
    private final UserNames names;

    private final TextField name = field(StoreDetailsService.MAX_NAME);
    private final TextField address = field(StoreDetailsService.MAX_ADDRESS);
    private final TextField proprietor = field(StoreDetailsService.MAX_PROPRIETOR);
    private final TextField tin = field(StoreDetailsService.MAX_TIN);
    private final TextField phone = field(StoreDetailsService.MAX_PHONE);
    private final Button save = SystemUi.applyButton("Save Changes");
    private final Button undo = SystemUi.secondaryButton("Undo Edits");
    private final Label message = new Label();
    private final TableView<StoreDetailsRecord> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label changesValue = new Label("0");
    private final Label lastValue = new Label("None");
    private final VBox view = new VBox(16);

    private List<StoreDetailsRecord> rows = List.of();

    StoreDetailsPane(ScreenContext screen, StoreDetailsService service) {
        this.screen = screen;
        this.service = service;
        this.names = new UserNames(screen);

        // Wrapped cells (up to two lines), so nothing is cut off with "..." in a narrow window.
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Date / Time", 105, 80,
                r -> SystemUi.wrappedCell(ROW_TIME.format(PhTime.of(r.performedAt())))));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Store Name", 170, 110,
                r -> SystemUi.wrappedCell(r.store().name())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Address", 280, 150,
                r -> SystemUi.wrappedCell(r.store().address())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Proprietor", 150, 100,
                r -> SystemUi.wrappedCell(r.store().proprietor())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("TIN", 130, 90,
                r -> SystemUi.wrappedCell(r.store().tin())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Phone", 110, 80,
                r -> SystemUi.wrappedCell(r.store().phone())));
        table.getColumns().add(Tables.<StoreDetailsRecord>textColumn("Terminal", 75, StoreDetailsRecord::terminalId));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Performed By", 170, 110,
                r -> SystemUi.wrappedCell(names.label(r.performedBy()))));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Status", 90, 80,
                r -> !rows.isEmpty() && rows.get(0).id() == r.id()
                        ? SystemUi.colouredCell("In use", Theme.SUCCESS) : SystemUi.wrappedCell("")));
        SystemUi.placeholder(table, EMPTY);
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        GridPane form = new GridPane();
        form.setHgap(16);
        form.setVgap(12);
        addRow(form, 0, 0, "Store Name *", name);
        addRow(form, 0, 2, "Proprietor", proprietor);
        addRow(form, 1, 0, "Address", address);
        addRow(form, 1, 2, "TIN", tin);
        addRow(form, 2, 2, "Phone", phone);
        for (int i = 0; i < 4; i++) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            if (i % 2 == 1) {
                c.setHgrow(Priority.ALWAYS);
                c.setFillWidth(true);
            }
            form.getColumnConstraints().add(c);
        }

        Label title = new Label("Store details on receipts");
        Styles.cardTitle(title);
        Label hint = new Label("Printed at the top of every receipt. Only the store name is "
                + "required. Each save is kept below with who made it.");
        Styles.cardText(hint);
        hint.setWrapText(true);

        save.setOnAction(e -> save());
        undo.setOnAction(e -> showCurrent());
        for (TextField f : new TextField[] {name, address, proprietor, tin, phone}) {
            f.setOnAction(e -> save());
        }
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(12, message, spacer, undo, save);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(message, Priority.SOMETIMES);

        VBox card = new VBox(12, title, hint, form, actions);
        Styles.card(card);
        card.setPadding(new Insets(20, 24, 20, 24));
        card.setMinHeight(Region.USE_PREF_SIZE);   // the table shrinks in a small window, never the form
        for (Region r : new Region[] {name, address, proprietor, tin, phone, undo, save}) {
            r.setMinHeight(Region.USE_PREF_SIZE);
        }
        table.setMinHeight(Tables.HEADER_HEIGHT + 2 * Tables.ROW_HEIGHT + 2);

        Label historyTitle = new Label("Change log");
        Styles.cardTitle(historyTitle);

        view.getChildren().addAll(card, historyTitle, table, SystemUi.footer(pager,
                SystemUi.tile("Changes", changesValue), SystemUi.tile("Last Change", lastValue)));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    @Override
    public Parent getView() {
        return view;
    }

    /** Called every time the tab is shown. */
    @Override
    public void refresh() {
        message.setText("");
        showCurrent();
        load();
    }

    /* --------------------------------- data --------------------------------- */

    private void showCurrent() {
        StoreInfo now = service.current();
        name.setText(now.name());
        address.setText(now.address());
        proprietor.setText(now.proprietor());
        tin.setText(now.tin());
        phone.setText(now.phone());
    }

    private void load() {
        try {
            rows = service.history(screen.sessionId());
            rows.stream().map(StoreDetailsRecord::performedBy).distinct().forEach(names::remember);
            SystemUi.placeholder(table, EMPTY);
        } catch (SystemException e) {
            loadFailed(e.getMessage());
        } catch (RuntimeException e) {
            loadFailed(UiErrors.message(e, "The store details history could not be loaded. Try again."));
        }
        changesValue.setText(SystemUi.count(rows.size()));
        lastValue.setText(rows.isEmpty() ? "None" : ROW_TIME.format(PhTime.of(rows.get(0).performedAt())));
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

    private void save() {
        if (!Modal.confirm(screen.stage(), "Save the store details?",
                "The next receipts will print these details. The change is logged with your "
                        + "name.", "Save", "Cancel")) {
            return;
        }
        try {
            StoreDetailsRecord saved = service.change(screen.sessionId(), name.getText(), address.getText(),
                    proprietor.getText(), tin.getText(), phone.getText());
            load();
            showCurrent();
            showInfo("Saved. Receipts now print \"" + saved.store().name() + "\".");
        } catch (SystemException e) {
            showError(e.getMessage());
        }
    }

    /* -------------------------------- helpers -------------------------------- */

    private static TextField field(int max) {
        TextField field = new TextField();
        Styles.input(field);
        field.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                change -> change.getControlNewText().length() <= max ? change : null));
        return field;
    }

    private static void addRow(GridPane form, int row, int column, String label, TextField field) {
        Label text = new Label(label);
        text.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        text.setMinWidth(Region.USE_PREF_SIZE);
        field.setMaxWidth(Double.MAX_VALUE);
        form.add(text, column, row);
        form.add(field, column + 1, row);
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
