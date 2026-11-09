package system.ui;

import app.ScreenContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
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
 * Store Details (Admin only, from Settings &gt; General &gt; System Configuration), as pop-ups:
 * - the details printed at the top of every receipt, with Edit and View Logs;
 * - Edit: change them (a save never edits an old row: it adds a new one, logged with who did it);
 * - View Logs: every change, newest first; the newest is the one receipts use.
 */
public final class StoreDetailsModals {

    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("M/d/yy HH:mm", Locale.US);
    private static final DateTimeFormatter LONG_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);
    private static final String EMPTY = "No changes yet. Receipts use the details shown until one is saved.";

    private StoreDetailsModals() { }

    /** The Store Details pop-up, over {@code owner}. */
    public static void show(ScreenContext screen, StoreDetailsService service, Window owner) {
        Modal modal = new Modal(owner, 680);
        UserNames names = new UserNames(screen);

        Label name = value();
        Label address = value();
        Label proprietor = value();
        Label tin = value();
        Label phone = value();
        Label changed = new Label();
        Styles.modalSubtitle(changed);
        changed.setWrapText(true);
        GridPane grid = Modal.grid();
        int r = 0;
        r = Modal.row(grid, r, "Store Name", name);
        r = Modal.row(grid, r, "Address", address);
        r = Modal.row(grid, r, "Proprietor", proprietor);
        r = Modal.row(grid, r, "TIN", tin);
        Modal.row(grid, r, "Phone", phone);

        Runnable fill = () -> {
            StoreInfo now = service.current();
            name.setText(now.name());
            address.setText(orDash(now.address()));
            proprietor.setText(orDash(now.proprietor()));
            tin.setText(orDash(now.tin()));
            phone.setText(orDash(now.phone()));
            try {
                List<StoreDetailsRecord> history = service.history(screen.sessionId());
                if (history.isEmpty()) {
                    changed.setText("Not changed yet: these are the details set when the app was installed.");
                } else {
                    StoreDetailsRecord last = history.get(0);
                    names.remember(last.performedBy());
                    changed.setText("Last changed " + LONG_TIME.format(PhTime.of(last.performedAt())) + " by "
                            + names.label(last.performedBy()) + " on " + last.terminalId() + ".");
                }
            } catch (SystemException | RuntimeException e) {
                changed.setText("");
            }
            modal.sizeToFit();
        };
        fill.run();

        Button close = button("Close", Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());
        Button logs = button("View Logs", Theme.INFORMATION, "white");
        logs.setOnAction(e -> showLogs(screen, service, modal.window()));
        Button edit = button("Edit", Theme.PRIMARY_BLUE, "white");
        edit.setDefaultButton(true);
        edit.setOnAction(e -> {
            if (showEdit(screen, service, modal.window())) {
                fill.run();
            }
        });

        modal.card().getChildren().addAll(Modal.title("Store Details"),
                Modal.subtitle("Printed at the top of every receipt, on every register."), grid, changed,
                Modal.buttons(close, logs, edit));
        modal.showAndWait();
    }

    /* ------------------------------------- Edit ------------------------------------- */

    /** The Edit pop-up. @return true if the details were saved */
    private static boolean showEdit(ScreenContext screen, StoreDetailsService service, Window owner) {
        Modal modal = new Modal(owner, 680);
        boolean[] saved = {false};
        StoreInfo now = service.current();
        TextField name = field(StoreDetailsService.MAX_NAME, now.name());
        TextField address = field(StoreDetailsService.MAX_ADDRESS, now.address());
        TextField proprietor = field(StoreDetailsService.MAX_PROPRIETOR, now.proprietor());
        TextField tin = field(StoreDetailsService.MAX_TIN, now.tin());
        TextField phone = field(StoreDetailsService.MAX_PHONE, now.phone());
        GridPane grid = Modal.grid();
        int r = 0;
        r = Modal.row(grid, r, "Store Name *", name);
        r = Modal.row(grid, r, "Address", address);
        r = Modal.row(grid, r, "Proprietor", proprietor);
        r = Modal.row(grid, r, "TIN", tin);
        Modal.row(grid, r, "Phone", phone);

        Label error = new Label();
        error.setWrapText(true);
        error.setManaged(false);
        error.setVisible(false);
        Styles.error(error);

        Button cancel = button("Cancel", Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button save = button("Save Changes", Theme.PRIMARY_BLUE, "white");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            try {
                service.change(screen.sessionId(), name.getText(), address.getText(), proprietor.getText(),
                        tin.getText(), phone.getText());
                saved[0] = true;
                modal.close();
            } catch (SystemException ex) {
                error.setText(ex.getMessage());
                error.setManaged(true);
                error.setVisible(true);
                modal.sizeToFit();
            }
        });

        modal.card().getChildren().addAll(Modal.title("Edit Store Details"),
                Modal.subtitle("Only the store name is required; a box left empty is left off the receipt. "
                        + "The change is logged with your name."), grid, error, Modal.buttons(cancel, save));
        modal.showAndWait();
        return saved[0];
    }

    /* ----------------------------------- View Logs ----------------------------------- */

    /** The change log pop-up: every change, newest first, with who made it. */
    private static void showLogs(ScreenContext screen, StoreDetailsService service, Window owner) {
        double width = Math.min(1100, Math.max(760, screen.stage().getWidth() - 200));
        Modal modal = new Modal(owner, width);
        UserNames names = new UserNames(screen);
        List<StoreDetailsRecord>[] rows = new List[] {List.of()};

        TableView<StoreDetailsRecord> table = Tables.create();
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Date / Time", 105, 80,
                r -> SystemUi.wrappedCell(ROW_TIME.format(PhTime.of(r.performedAt())))));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Store Name", 160, 100,
                r -> SystemUi.wrappedCell(r.store().name())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Address", 240, 140,
                r -> SystemUi.wrappedCell(r.store().address())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Proprietor", 140, 90,
                r -> SystemUi.wrappedCell(r.store().proprietor())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("TIN", 120, 80,
                r -> SystemUi.wrappedCell(r.store().tin())));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Phone", 100, 80,
                r -> SystemUi.wrappedCell(r.store().phone())));
        table.getColumns().add(Tables.<StoreDetailsRecord>textColumn("Terminal", 95, StoreDetailsRecord::terminalId));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Performed By", 160, 100,
                r -> SystemUi.wrappedCell(names.label(r.performedBy()))));
        table.getColumns().add(Tables.<StoreDetailsRecord>nodeColumn("Status", 80, 70,
                r -> !rows[0].isEmpty() && rows[0].get(0).id() == r.id()
                        ? SystemUi.colouredCell("In use", Theme.SUCCESS) : SystemUi.wrappedCell("")));
        table.setPrefHeight(Tables.HEADER_HEIGHT + 6 * Tables.ROW_HEIGHT + 2);
        table.setMinHeight(Region.USE_PREF_SIZE);

        Label changes = new Label("0");
        Label last = new Label("None");
        TablePager[] pager = new TablePager[1];
        pager[0] = TablePager.boxed(() -> {
            pager[0].setTotal(rows[0].size());
            table.getItems().setAll(pager[0].slice(rows[0]));
        });
        pager[0].setPageSize(6);

        Label error = new Label();
        error.setWrapText(true);
        Styles.error(error);
        try {
            rows[0] = service.history(screen.sessionId());
            rows[0].stream().map(StoreDetailsRecord::performedBy).distinct().forEach(names::remember);
            SystemUi.placeholder(table, EMPTY);
        } catch (SystemException e) {
            error.setText(e.getMessage());
            SystemUi.placeholder(table, SystemUi.NOT_LOADED);
        } catch (RuntimeException e) {
            error.setText(UiErrors.message(e, "The store details log could not be loaded. Try again."));
            SystemUi.placeholder(table, SystemUi.NOT_LOADED);
        }
        changes.setText(SystemUi.count(rows[0].size()));
        last.setText(rows[0].isEmpty() ? "None" : ROW_TIME.format(PhTime.of(rows[0].get(0).performedAt())));
        pager[0].reset();
        pager[0].setTotal(rows[0].size());
        table.getItems().setAll(pager[0].slice(rows[0]));

        Button close = button("Close", Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(e -> modal.close());

        VBox body = new VBox(12, table, SystemUi.footer(pager[0],
                SystemUi.tile("Changes", changes), SystemUi.tile("Last Change", last)));
        if (!error.getText().isEmpty()) {
            body.getChildren().add(0, error);
        }
        modal.card().getChildren().addAll(Modal.title("Store Details Log"),
                Modal.subtitle("Every change, newest first, with who made it. The newest is the one receipts use."),
                body, Modal.buttons(close));
        modal.showAndWait();
    }

    /* ------------------------------------ helpers ------------------------------------ */

    private static Label value() {
        Label label = new Label();
        Styles.modalValue(label);
        label.setWrapText(true);
        return label;
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    private static TextField field(int max, String text) {
        TextField field = new TextField(text);
        Styles.input(field);
        field.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                change -> change.getControlNewText().length() <= max ? change : null));
        return field;
    }

    static Button button(String text, String background, String textColour) {
        Button b = new Button(text);
        Styles.modalButton(b, background, textColour);
        return b;
    }
}
