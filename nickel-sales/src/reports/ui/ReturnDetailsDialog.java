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
import pos.contract.PriceType;
import pos.contract.ReturnLineRecord;
import pos.contract.ReturnRecord;
import pos.contract.SaleRecord;
import shared.Modal;
import shared.PhTime;
import shared.Quantity;
import shared.Styles;
import shared.Theme;

/**
 * Return Details: one return (POS Void Item / Void Transaction) with what was returned, the refund
 * and its VAT, and the sale it came from. Read-only, built from the {@link ReturnRecord} alone, like
 * {@link SaleDetailsDialog}. View Sale opens the original sale.
 */
final class ReturnDetailsDialog {

    private ReturnDetailsDialog() { }

    /** @return true if View Sale was pressed (the caller then shows the original sale) */
    static boolean show(Window owner, SaleRecord sale, ReturnRecord ret, String voidedBy) {
        Modal modal = new Modal(owner, 820);
        boolean[] viewSale = {false};

        GridPane info = new GridPane();
        info.setHgap(24);
        info.setVgap(6);
        int r = 0;
        r = infoRow(info, r, "Return No.", ret.returnNo());
        r = infoRow(info, r, "Date / Time", PhTime.format(ret.returnedAt()));
        r = infoRow(info, r, "Terminal", ret.terminalId());
        r = infoRow(info, r, "Voided By", voidedBy);
        r = infoRow(info, r, "Sales Invoice", sale.invoiceNo() + "  (" + PhTime.format(sale.completedAt()) + ")");
        infoRow(info, r, "Kind", ret.fullVoid() ? "Full void (nothing left on the sale)" : "Partial void");

        // ---- what was returned ----
        GridPane lines = new GridPane();
        lines.setHgap(12);
        lines.setVgap(6);
        double[] percents = {5, 37, 12, 16, 12, 18};
        boolean[] right = {false, false, true, true, false, true};
        for (int i = 0; i < percents.length; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(percents[i]);
            c.setHalignment(right[i] ? HPos.RIGHT : HPos.LEFT);
            lines.getColumnConstraints().add(c);
        }
        String[] heads = {"#", "Product", "Qty", "Unit Price", "Price", "Refund"};
        for (int i = 0; i < heads.length; i++) {
            Label h = new Label(heads[i]);
            Styles.detailKey(h);
            lines.add(h, i, 0);
        }
        int row = 1;
        for (ReturnLineRecord line : ret.lines()) {
            lines.add(text(String.valueOf(line.saleLineNo())), 0, row);   // the line of the sale it came from
            Label name = text(line.productName() + (line.vatable() ? "" : "  (VAT-exempt)"));
            name.setWrapText(true);
            lines.add(name, 1, row);
            lines.add(text("-" + Quantity.format(line.quantity()) + " " + line.unit()), 2, row);
            lines.add(text(ReportsUi.peso(line.unitPrice())), 3, row);
            lines.add(text(line.priceType() == PriceType.WHOLESALE ? "Wholesale" : "Retail"), 4, row);
            lines.add(text("-" + ReportsUi.peso(line.lineTotal())), 5, row);
            row++;
        }
        ScrollPane lineScroll = new ScrollPane(lines);
        lineScroll.setFitToWidth(true);
        lineScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        lineScroll.setPrefViewportHeight(Math.min(260, 34.0 * (ret.lines().size() + 1)));
        lineScroll.setStyle("-fx-background-color: transparent; -fx-background: " + Theme.SURFACE + ";");

        // ---- refund ----
        GridPane totals = new GridPane();
        totals.setHgap(24);
        totals.setVgap(6);
        int t = 0;
        t = infoRow(totals, t, "Items Returned", Quantity.format(ret.pieces()));
        t = infoRow(totals, t, "VATable Sales", "-" + ReportsUi.peso(ret.vatableSales()));
        t = infoRow(totals, t, "VAT-exempt Sales", "-" + ReportsUi.peso(ret.vatExemptSales()));
        t = infoRow(totals, t, "VAT (12%)", "-" + ReportsUi.peso(ret.vatAmount()));
        Label totalKey = text("Total Refunded");
        Label totalValue = text("-" + ReportsUi.peso(ret.total()));
        totalKey.setStyle(totalKey.getStyle() + " -fx-font-weight: bold;");
        totalValue.setStyle(totalValue.getStyle() + " -fx-font-weight: bold; -fx-text-fill: " + Theme.ERROR + ";");
        totals.add(totalKey, 0, t);
        totals.add(totalValue, 1, t++);
        infoRow(totals, t, "Refund Method", TransactionsPane.methodLabel(ret.refundMethod())
                + (ret.refundReference() == null ? "" : "  (ref. " + ret.refundReference() + ")"));

        Button sale2 = new Button("View Sale");
        Styles.modalButton(sale2, Theme.BORDER, Theme.PRIMARY_TEXT);
        sale2.setOnAction(e -> {
            viewSale[0] = true;
            modal.close();
        });
        Button close = new Button("Close");
        Styles.modalButton(close, Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title("Return Details"), info, rule(), lineScroll, rule(), totals,
                Modal.buttons(sale2, close));
        modal.showAndWait();
        return viewSale[0];
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
