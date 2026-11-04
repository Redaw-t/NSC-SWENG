package barcode.ui;

import barcode.service.BarcodeRow;
import barcode.service.BarcodeService;
import barcode.service.BarcodeStatus;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import product.contract.BarcodeAssignException;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.Theme;
import shared.UiErrors;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Barcode" tab of the Products screen: search, a Barcode Status filter, "+ Assign Barcode"
 * and a table of every sold product with its barcode, status and last update. Each row has the
 * buttons that fit it: View, Reassign and Print when it has a barcode, Assign when it has none.
 * Saving and printing go through {@link BarcodeService}; the services check permissions.
 * Same look and paging as the Products tab (the page size follows the table height).
 */
public final class BarcodePane {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d/yy", Locale.US);

    /** The choices of the Barcode Status drop-down. */
    private enum StatusFilter {
        ALL("All Barcode Status", null),
        ASSIGNED("Assigned", BarcodeStatus.ASSIGNED),
        UNASSIGNED("Unassigned", BarcodeStatus.UNASSIGNED),
        ERROR("Duplicate / Error", BarcodeStatus.ERROR);

        private final String label;
        private final BarcodeStatus status;

        StatusFilter(String label, BarcodeStatus status) {
            this.label = label;
            this.status = status;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final BarcodeService service;

    private final TextField search = new TextField();
    private final SearchPicker<StatusFilter> statusFilter = new SearchPicker<>(false, false);
    private final TableView<BarcodeRow> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label message = new Label();
    private final VBox view = new VBox(16);

    private List<BarcodeRow> loaded = List.of();     // everything from the Product module
    private List<BarcodeRow> filtered = List.of();   // loaded, narrowed by search and status

    public BarcodePane(BarcodeService service) {
        this.service = service;

        table.getColumns().add(Tables.<BarcodeRow>textColumn("Product Code", 130, r -> r.product().productId()));
        table.getColumns().add(Tables.<BarcodeRow>textColumn("Product Name", 250, r -> r.product().name()));
        table.getColumns().add(Tables.<BarcodeRow>textColumn("Barcode", 180, r -> r.barcode() == null ? "-" : r.barcode()));
        table.getColumns().add(Tables.<BarcodeRow>nodeColumn("Barcode Status", 170, 150,
                r -> BarcodeUi.pill(r.status(), r.problem())));
        table.getColumns().add(Tables.<BarcodeRow>textColumn("Last Updated", 120,
                r -> r.barcode() == null || r.lastUpdated() == null ? "-" : DATE.format(r.lastUpdated())));
        table.getColumns().add(Tables.<BarcodeRow>nodeColumn("Actions", 270, 240, this::actions));
        table.setPlaceholder(new Label("No products found."));
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        search.setPromptText("Search Product...");
        search.setPrefWidth(300);
        Styles.input(search);
        search.textProperty().addListener((obs, old, text) -> applyFilters());

        statusFilter.setPrefWidth(220);
        statusFilter.getItems().setAll(StatusFilter.values());
        statusFilter.setValue(StatusFilter.ALL);
        statusFilter.valueProperty().addListener((obs, old, s) -> applyFilters());

        Button assign = new Button("+ Assign Barcode");
        Styles.primaryButton(assign);
        assign.setOnAction(e -> openAssign(null));

        HBox toolbar = new HBox(12, search, statusFilter, assign);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        message.setWrapText(true);
        Styles.error(message);

        view.getChildren().addAll(toolbar, table, message, pager.getView());
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    public Parent getView() {
        return view;
    }

    /** Called every time the tab is shown: reloads the list from the Product module. */
    public void refresh() {
        reload();
    }

    /* ------------------------------- table rows ------------------------------ */

    private Node actions(BarcodeRow row) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER);
        if (row.status() == BarcodeStatus.UNASSIGNED) {
            Button assign = BarcodeUi.rowButton("Assign", true);
            assign.setOnAction(e -> openAssign(row));
            box.getChildren().add(assign);
            return box;
        }
        Button viewButton = BarcodeUi.rowButton("View", false);
        viewButton.setOnAction(e -> BarcodeDetailsDialog.show(owner(), row));
        Button reassign = BarcodeUi.rowButton("Reassign", true);
        reassign.setOnAction(e -> openReassign(row));
        box.getChildren().addAll(viewButton, reassign);
        if (row.printable()) {   // a wrong or shared barcode must be fixed before it is printed
            Button print = BarcodeUi.rowButton("Print", false);
            print.setOnAction(e -> openPrint(row));
            box.getChildren().add(print);
        }
        return box;
    }

    /* -------------------------------- actions -------------------------------- */

    /** "+ Assign Barcode" (preselect == null) or the row's Assign button (preselect = that product). */
    private void openAssign(BarcodeRow preselect) {
        List<BarcodeRow> without = loaded.stream().filter(r -> r.status() == BarcodeStatus.UNASSIGNED).toList();
        message.setText("");
        boolean saved = preselect == null
                ? AssignBarcodeDialog.show(owner(), service, without, null)
                : AssignBarcodeDialog.show(owner(), service, without, null, preselect);
        if (saved) {
            reload();
        }
    }

    private void openReassign(BarcodeRow row) {
        message.setText("");
        if (AssignBarcodeDialog.show(owner(), service, List.of(), row)) {
            reload();
        }
    }

    private void openPrint(BarcodeRow row) {
        message.setText("");
        int printed = PrintLabelDialog.show(owner(), service, row);
        if (printed > 0) {
            showSuccess("Sent " + printed + (printed == 1 ? " label" : " labels") + " for \""
                    + row.product().name() + "\" to the printer.");
        }
    }

    private void showSuccess(String text) {
        message.setText(text);
        message.setStyle("-fx-text-fill: " + Theme.SUCCESS + ";");
    }

    /* ------------------------------ loading & paging ------------------------------ */

    private void reload() {
        try {
            loaded = service.rows();
            message.setText("");
            message.setStyle("");
            Styles.error(message);
        } catch (BarcodeAssignException e) {
            loaded = List.of();
            Styles.error(message);
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            loaded = List.of();
            Styles.error(message);
            message.setText(UiErrors.message(e));
        }
        applyFilters();
    }

    /** Narrows {@link #loaded} by the search text and the status, and goes to page 1. */
    private void applyFilters() {
        String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        StatusFilter choice = statusFilter.getValue();
        List<BarcodeRow> result = new ArrayList<>();
        for (BarcodeRow r : loaded) {
            boolean statusOk = choice == null || choice.status == null || choice.status == r.status();
            boolean textOk = needle.isEmpty()
                    || r.product().name().toLowerCase(Locale.ROOT).contains(needle)
                    || r.product().productId().toLowerCase(Locale.ROOT).contains(needle)
                    || (r.barcode() != null && r.barcode().toLowerCase(Locale.ROOT).contains(needle));
            if (statusOk && textOk) {
                result.add(r);
            }
        }
        filtered = result;
        pager.reset();
        showPage();
    }

    private void showPage() {
        pager.setTotal(filtered.size());
        table.getItems().setAll(pager.slice(filtered));
    }

    private Window owner() {
        return view.getScene() == null ? null : view.getScene().getWindow();
    }
}
