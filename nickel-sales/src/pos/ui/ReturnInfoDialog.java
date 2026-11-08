package pos.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.checkout.PaymentService;
import pos.contract.PriceType;
import pos.contract.ReturnLineRecord;
import pos.contract.ReturnRecord;
import pos.contract.SaleRecord;
import shared.Modal;
import shared.Money;
import shared.Quantity;
import shared.Styles;
import shared.Theme;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

/**
 * A return number scanned (or typed) in the barcode box or in F7 Retrieve Transaction: a read-only
 * summary of that return. It shows the return #, the sales invoice it came from, when / where / who,
 * the items returned, the refund method (and reference) and the totals with VAT. Nothing can be
 * changed here; Reprint Slip [ENTER] reprints its RETURN slip, Close [ESC] closes.
 */
final class ReturnInfoDialog {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    private ReturnInfoDialog() { }

    /**
     * @param names user ID -> the person's name (may return null: the ID is shown)
     * @return true if Reprint Slip was pressed
     */
    static boolean show(Window owner, SaleRecord sale, ReturnRecord ret, Function<String, String> names) {
        Modal modal = new Modal(owner, 680);
        boolean[] reprint = {false};

        GridPane details = Modal.grid();
        int row = 0;
        row = Modal.row(details, row, "Return #", value(ret.returnNo(), true));
        row = Modal.row(details, row, "Date & Time", value(WHEN.format(shared.PhTime.of(ret.returnedAt())), false));
        row = Modal.row(details, row, "POS Terminal", value(ret.terminalId(), false));
        row = Modal.row(details, row, "Cashier", value(name(names, ret.cashierUserId()), false));
        row = Modal.row(details, row, "Sales Invoice #", value(sale.invoiceNo()
                + "  (" + WHEN.format(shared.PhTime.of(sale.completedAt())) + ", " + sale.terminalId() + ")", false));
        String how = PaymentService.label(ret.refundMethod())
                + (ret.refundReference() == null ? "" : "  ·  Ref. " + ret.refundReference());
        Modal.row(details, row, "Refund Method", value(how, true));

        // items returned
        VBox items = new VBox(6);
        for (ReturnLineRecord line : ret.lines()) {
            String detail = Quantity.format(line.quantity()) + " " + line.unit() + " x " + Money.format(line.unitPrice())
                    + (line.priceType() == PriceType.WHOLESALE ? " W" : "") + (line.vatable() ? "  V" : "  E");
            items.getChildren().add(pair(line.productName() + "\n" + detail, "- " + PosScreen.peso(line.lineTotal()), false));
        }
        ScrollPane itemScroll = new ScrollPane(items);
        itemScroll.setFitToWidth(true);
        itemScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        itemScroll.setPrefViewportHeight(Math.min(180, 52.0 * ret.lines().size()));
        itemScroll.setFocusTraversable(false);
        Styles.slimScrollBar(itemScroll);
        itemScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        Label itemsTitle = new Label("Items Returned  (" + Quantity.format(ret.pieces()) + ")");
        itemsTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");

        VBox totals = new VBox(4,
                pair("VATable (V)", "- " + Money.format(ret.vatableSales()), false),
                pair("VAT", "- " + Money.format(ret.vatAmount()), false),
                pair("VAT-Exempt (E)", "- " + Money.format(ret.vatExemptSales()), false),
                pair("TOTAL REFUNDED", "- " + PosScreen.peso(ret.total()), true));

        Button close = PosStyle.modalKeyButton("Close", "ESC", PosStyle.Kind.SECONDARY);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());
        Button print = PosStyle.modalKeyButton("Reprint Slip", "ENTER", PosStyle.Kind.PRIMARY);
        print.setDefaultButton(true);
        print.setOnAction(e -> {
            reprint[0] = true;
            modal.close();
        });

        modal.card().getChildren().addAll(Modal.title("Return Details"),
                Modal.subtitle((ret.fullVoid() ? "Full void" : "Partial void") + " of invoice " + sale.invoiceNo()),
                details, divider(), itemsTitle, itemScroll, divider(), totals, Modal.buttons(close, print));
        modal.showAndWait();
        return reprint[0];
    }

    private static String name(Function<String, String> names, String userId) {
        try {
            String n = names.apply(userId);
            return n == null || n.isBlank() ? userId : n;
        } catch (RuntimeException e) {
            return userId;
        }
    }

    private static Label value(String text, boolean bold) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";" + (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private static HBox pair(String left, String right, boolean strong) {
        String style = "-fx-font-size: " + (strong ? 18 : 15) + "px; -fx-text-fill: "
                + (strong ? PosScreen.RED : Theme.PRIMARY_TEXT) + ";" + (strong ? " -fx-font-weight: bold;" : "");
        Label l = new Label(left);
        l.setWrapText(true);
        l.setStyle(style);
        Label r = new Label(right);
        r.setStyle(style);
        r.setMinWidth(Region.USE_PREF_SIZE);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox box = new HBox(8, l, grow, r);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(0, 4, 0, 0));
        return box;
    }

    private static Region divider() {
        Region r = new Region();
        r.setMinHeight(1);
        r.setPrefHeight(1);
        r.setMaxHeight(1);
        r.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return r;
    }
}
