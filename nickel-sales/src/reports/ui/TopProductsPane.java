package reports.ui;

import app.ScreenContext;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import reports.model.ProductRanking;
import reports.model.ProductSales;
import reports.model.ReportPeriod;
import reports.service.ProductPerformanceService;
import reports.service.ReportException;
import reports.ui.ReportsUi.Choice;
import shared.Quantity;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Tables;
import shared.UiErrors;

import java.util.List;

/**
 * The "Top Products" tab: the best-selling products of a period by quantity or by sales amount,
 * with each one's share of the period's sales. Ranking and tie-breaks are the service's
 * ({@link ProductPerformanceService}); this only shows the rows.
 */
final class TopProductsPane implements ReportsPane {

    private static final List<Integer> SIZES = List.of(5, 10, 20, 50);

    private final ScreenContext screen;
    private final ProductPerformanceService products;

    private final PeriodControls period = new PeriodControls(this::load);
    private final SearchPicker<ProductRanking> ranking = new SearchPicker<>(false, false);
    private final SearchPicker<Choice<Integer>> size = new SearchPicker<>(false, false);
    private final TableView<ProductSales> table = Tables.create();
    private final TablePager pager = TablePager.boxed(this::showPage);
    private final Label message = ReportsUi.messageLabel();
    private final VBox view = new VBox(16);

    private List<ProductSales> rows = List.of();
    private boolean resetting;


    TopProductsPane(ScreenContext screen, ProductPerformanceService products) {
        this.screen = screen;
        this.products = products;

        table.getColumns().add(Tables.numberColumn(pager));
        table.getColumns().get(0).setText("Rank");
        table.getColumns().add(Tables.<ProductSales>textColumn("Product", 260, ProductSales::name));
        table.getColumns().add(Tables.<ProductSales>textColumn("Product ID", 120, ProductSales::productId));
        table.getColumns().add(Tables.<ProductSales>textColumn("Qty Sold", 110,
                p -> Quantity.format(p.quantity()) + " " + p.unit()));
        table.getColumns().add(Tables.<ProductSales>textColumn("Sales Amount", 140, p -> ReportsUi.peso(p.amount())));
        table.getColumns().add(Tables.<ProductSales>textColumn("Share of Sales", 120,
                p -> p.sharePercent().toPlainString() + "%"));
        ReportsUi.placeholder(table, "No products were sold in this period.");
        pager.fitToHeight(table, Tables.HEADER_HEIGHT, Tables.ROW_HEIGHT, this::showPage);

        ranking.getItems().setAll(ProductRanking.values());
        ranking.setValue(ProductRanking.BY_QUANTITY);
        ranking.setPrefWidth(190);
        for (int n : SIZES) {
            size.getItems().add(new Choice<>(n, "Top " + n));
        }
        size.setValue(size.getItems().get(1));
        size.setPrefWidth(110);

        ranking.valueProperty().addListener((o, was, is) -> {
            if (!resetting) {
                load();
            }
        });
        size.valueProperty().addListener((o, was, is) -> {
            if (!resetting) {
                load();
            }
        });
        Button reset = ReportsUi.resetButton(() -> {
            resetting = true;
            period.reset();
            ranking.setValue(ProductRanking.BY_QUANTITY);
            size.setValue(size.getItems().get(1));
            resetting = false;
            load();
        });
        FlowPane toolbar = new FlowPane(12, 12, period.getView(), ranking, size, reset);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label("Sales amounts include VAT. Share = the product's amount out of all products sold "
                + "in the period.");
        Styles.muted(note);
        note.setWrapText(true);

        view.getChildren().addAll(toolbar, message, table, note, pager.getView());
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    @Override
    public Parent getView() {
        return view;
    }

    @Override
    public void refresh() {
        load();
    }

    private void load() {
        ReportPeriod p;
        try {
            p = period.period();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        try {
            ProductRanking by = ranking.getValue() == null ? ProductRanking.BY_QUANTITY : ranking.getValue();
            int n = size.getValue() == null ? 10 : size.getValue().value();
            rows = products.top(screen.sessionId(), p, by, n);
            message.setText("");
        } catch (ReportException e) {
            rows = List.of();
            showError(e.getMessage());
        } catch (RuntimeException e) {
            rows = List.of();
            showError(UiErrors.message(e, "The product report could not be loaded. Try again."));
        }
        pager.reset();
        showPage();
    }

    private void showPage() {
        pager.setTotal(rows.size());
        table.getItems().setAll(pager.slice(rows));
    }

    private void showError(String text) {
        message.setText(text);
        Styles.error(message);
    }
}
