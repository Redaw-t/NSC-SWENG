package reports.ui;

import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import shared.Modal;
import shared.PhTime;
import shared.Quantity;
import shared.Styles;
import shared.Theme;

/**
 * Sale Details: one completed sale with its lines, VAT totals and payments. Read-only, built
 * from the {@link SaleRecord} alone (POS's receipt classes are POS internals, so Reports does
 * not use them).
 */
final class SaleDetailsDialog {

    private SaleDetailsDialog() { }

    static void show(Window owner, SaleRecord sale, String cashier) {
        Modal modal = new Modal(owner, 820);

        GridPane info = new GridPane();
        info.setHgap(24);
        info.setVgap(6);
        int r = 0;
        r = infoRow(info, r, "Invoice No.", sale.invoiceNo());
        r = infoRow(info, r, "Date / Time", PhTime.format(sale.completedAt()));
        r = infoRow(info, r, "Terminal", sale.terminalId());
        infoRow(info, r, "Cashier", cashier);

        // ---- the lines ----
        GridPane lines = new GridPane();
        lines.setHgap(12);
        lines.setVgap(6);
        // Shares of the pop-up's width, so the columns always fit (amounts right-aligned).
        double[] percents = {5, 37, 12, 16, 12, 18};
        boolean[] right = {false, false, true, true, false, true};
        for (int i = 0; i < percents.length; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(percents[i]);
            c.setHalignment(right[i] ? HPos.RIGHT : HPos.LEFT);
            lines.getColumnConstraints().add(c);
        }
        String[] heads = {"#", "Product", "Qty", "Unit Price", "Price", "Amount"};
        for (int i = 0; i < heads.length; i++) {
            Label h = new Label(heads[i]);
            Styles.detailKey(h);
            lines.add(h, i, 0);
        }
        int row = 1;
        for (SaleLineRecord line : sale.lines()) {
            lines.add(text(String.valueOf(line.lineNo())), 0, row);
            Label name = text(line.productName() + (line.vatable() ? "" : "  (VAT-exempt)"));
            name.setWrapText(true);
            lines.add(name, 1, row);
            lines.add(text(Quantity.format(line.quantity()) + " " + line.unit()), 2, row);
            lines.add(text(ReportsUi.peso(line.unitPrice())), 3, row);
            lines.add(text(line.priceType() == PriceType.WHOLESALE ? "Wholesale" : "Retail"), 4, row);
            lines.add(text(ReportsUi.peso(line.lineTotal())), 5, row);
            row++;
        }
        ScrollPane lineScroll = new ScrollPane(lines);
        lineScroll.setFitToWidth(true);
        lineScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        lineScroll.setPrefViewportHeight(Math.min(260, 34.0 * (sale.lines().size() + 1)));
        lineScroll.setStyle("-fx-background-color: transparent; -fx-background: " + Theme.SURFACE + ";");

        // ---- totals and payments ----
        GridPane totals = new GridPane();
        totals.setHgap(24);
        totals.setVgap(6);
        int t = 0;
        t = infoRow(totals, t, "VATable Sales", ReportsUi.peso(sale.vatableSales()));
        t = infoRow(totals, t, "VAT-exempt Sales", ReportsUi.peso(sale.vatExemptSales()));
        t = infoRow(totals, t, "VAT (12%)", ReportsUi.peso(sale.vatAmount()));
        Label totalKey = text("Total");
        Label totalValue = text(ReportsUi.peso(sale.total()));
        totalKey.setStyle(totalKey.getStyle() + " -fx-font-weight: bold;");
        totalValue.setStyle(totalValue.getStyle() + " -fx-font-weight: bold;");
        totals.add(totalKey, 0, t);
        totals.add(totalValue, 1, t++);
        for (PaymentRecord p : sale.payments()) {
            String paid = TransactionsPane.methodLabel(p.method()) + " " + ReportsUi.peso(p.amountTendered())
                    + (p.referenceNo() == null ? "" : "  (ref. " + p.referenceNo() + ")")
                    + (p.change().signum() > 0 ? "  -  change " + ReportsUi.peso(p.change()) : "");
            t = infoRow(totals, t, sale.payments().size() == 1 ? "Payment" : "Payment " + p.paymentNo(), paid);
        }
        if (sale.subtotal().compareTo(sale.total()) == 0 && sale.vatAmount().signum() > 0) {
            Label note = new Label("Sold when shelf prices already included VAT: the amounts above include VAT.");
            Styles.muted(note);
            note.setWrapText(true);
            totals.add(note, 0, t, 2, 1);
        }

        Button close = new Button("Close");
        Styles.modalButton(close, Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title("Sale Details"), info, rule(), lineScroll, rule(), totals,
                Modal.buttons(close));
        modal.showAndWait();
    }

    private static int infoRow(GridPane grid, int row, String key, String value) {
        if (value == null || value.isBlank()) {
            return row;
        }
        Label k = new Label(key);
        Styles.detailKey(k);
        k.setMinWidth(140);
        grid.add(k, 0, row);
        grid.add(text(value), 1, row);
        return row + 1;
    }

    private static Label text(String s) {
        Label label = new Label(s);
        label.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return label;
    }

    private static Node rule() {
        Region rule = new Region();
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        rule.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return rule;
    }
}
