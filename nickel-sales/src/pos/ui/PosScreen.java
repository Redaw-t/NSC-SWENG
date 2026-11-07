package pos.ui;

import app.HomeScreen;
import app.ScreenContext;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.Popup;
import inventory.contract.InsufficientStockException;
import pos.cart.CartException;
import pos.cart.Sale;
import pos.cart.SaleLineItem;
import pos.cart.SaleTotals;
import pos.cart.SalesService;
import pos.cart.StockProblem;
import pos.checkout.CheckoutException;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleLineRecord;
import pos.contract.SaleRecord;
import pos.receipt.Receipt;
import pos.receipt.ReceiptPrintException;
import product.contract.ProductInfo;
import product.contract.ProductLookup;
import shared.Modal;
import shared.Money;
import shared.Quantity;
import shared.ScaledPane;
import shared.Styles;
import shared.Theme;
import shared.UiErrors;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cashier's screen (POSScreen in the class diagram), laid out like the POS mockup.
 * Everything works from the keyboard; a barcode scanner acts as a keyboard that types the
 * code and presses Enter.
 *
 * | Key          | Does                                                          |
 * | Enter        | (barcode box) add the item; (cart) change the line's quantity  |
 * | F1           | search for a product (items without a barcode)                 |
 * | F2           | open item: sell something not in the catalog (no stock, not saved as a product) |
 * | F3 / Enter   | (cart) change the selected line's quantity                     |
 * | F4           | (cart) edit the selected line's price for this sale only (PRICE EDITED) |
 * | + / -        | (cart) one more / one less of the selected line                |
 * | Delete       | (cart) remove the selected line                                |
 * | Up / Down    | move in the cart (from the barcode box too)                    |
 * | F5           | pay (only when the sale can be paid)                           |
 * | F6           | reprint a receipt by invoice number (retrieved sale: its receipt) |
 * | F7           | Retrieve Transaction                       (NOT BUILT YET)     |
 * | F8           | New Transaction                            (NOT BUILT YET)     |
 * | F9           | cancel the transaction                                         |
 * | Esc          | back to the home screen (the sale stays open)                  |
 *
 * Quantity before scanning: type {@code 5*4800000000019} in the barcode box to add 5.
 *
 * Features marked NOT BUILT YET are skeletons: the button and key exist and show a short
 * notice. Search this file for "TODO(skeleton)" to find the methods to fill in.
 *
 * The layout is drawn for a 1920 px wide window and scaled to fit (see shared.ScaledPane);
 * the look is in PosStyle. All rules live in SalesService and
 * PaymentService; this screen only shows their results and messages.
 */
public final class PosScreen {

    static final String BLUE = HomeScreen.BLUE;
    static final String RED = "#a61c1c";
    static final String GREEN = "#1d6b34";
    static final String PESO = "₱";

    private static final double DESIGN_WIDTH = 1920;
    private static final String STATUS_BAR = "#C8D1D9";
    private static final String ONLINE_GREEN = "#1FBA42";
    private static final String DARK = Theme.PRIMARY_TEXT;
    private static final double MENU_WIDTH = 220;
    /** How faint locked buttons look while a retrieved sale is shown (JavaFX's own disabled look is 0.4). */
    private static final double LOCKED_OPACITY = 0.55;
    private static final java.time.format.DateTimeFormatter RETRIEVED_TIME =
            java.time.format.DateTimeFormatter.ofPattern("M/d/yy h:mm a", java.util.Locale.US);

    /** "5*4800000000019": a quantity, a star, then the barcode (or the search text). */
    private static final Pattern QUANTITY_PREFIX = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*\\*\\s*(.*)$");

    private final ScreenContext screen;
    private final SalesService pos;
    private final PaymentService payments;
    private final ProductLookup products;

    private final StackPane content = new StackPane();   // the screen; the payment card is added on top of it
    private final ScaledPane view = new ScaledPane(content, DESIGN_WIDTH);
    private final TextField barcodeField = new TextField();
    private final HBox barcodeBox = new HBox();
    private final TableView<Row> table = new TableView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Label message = new Label();
    private final Tooltip messageTip = new Tooltip();
    private final Label itemCount = new Label();
    private final Label subtotalValue = new Label();
    private final Label vatValue = new Label();
    private final Label vatableValue = new Label();   // VATable sales: the VATable lines without their VAT
    private final Label exemptValue = new Label();
    private final Label zeroRatedValue = new Label();
    private final Label totalValue = new Label();
    private final Label payHint = new Label();
    private Button payButton;
    private PaymentOverlay paymentCard;   // non-null while the payment card is open
    private String lastInvoice;           // the latest sale completed here; filled in for F6 reprint and F7 retrieve

    /** The completed sale shown by F7 Retrieve Transaction; null = the normal cart is shown. */
    private SaleRecord retrieved;
    private final Label summaryTitle = new Label("ORDER SUMMARY");
    /** Every button that is locked (dimmed, not clickable) while a retrieved sale is shown. */
    private final List<Button> lockedWhileRetrieved = new java.util.ArrayList<>();
    private Button removeButton;   // "Remove Item" normally, "Void Item" while a sale is retrieved
    private Button cancelButton;   // "Cancel Transaction" normally, "Void Transaction" while retrieved
    /** Dimmed while the cart is empty (already ready for a new sale); always on while a sale is retrieved. */
    private Button newTransactionButton;

    /**
     * One line in the cart table: a line of the open sale ({@code line}, with its stock problem or
     * null), or a line of a retrieved, already completed sale ({@code saved}; {@code line} is null).
     */
    record Row(int no, SaleLineItem line, SaleLineRecord saved, StockProblem problem, boolean priceEdited) {

        Row(int no, SaleLineItem line, StockProblem problem, boolean priceEdited) {
            this(no, line, null, problem, priceEdited);
        }

        /** A completed sale's line; PRICE EDITED comes from its saved original price (SaleLine.OriginalPrice). */
        static Row of(SaleLineRecord saved) {
            return new Row(saved.lineNo(), null, saved, null, saved.priceEdited());
        }

        String productName() {
            return line != null ? line.productName() : saved.productName();
        }

        boolean openItem() {
            return line != null ? line.openItem() : saved.openItem();
        }

        /**
         * The Item column: the name, with "(OPEN-ITEM)" after open items and "(PRICE EDITED)" after
         * lines whose price was changed with F4 (screen only, not the receipt).
         */
        String itemLabel() {
            return productName() + (openItem() ? " (OPEN-ITEM)" : "") + (priceEdited ? " (PRICE EDITED)" : "");
        }

        BigDecimal quantity() {
            return line != null ? line.quantity() : saved.quantity();
        }

        String unit() {
            return line != null ? line.unit() : saved.unit();
        }

        BigDecimal unitPrice() {
            return line != null ? line.unitPrice() : saved.unitPrice();
        }

        PriceType priceType() {
            return line != null ? line.priceType() : saved.priceType();
        }

        BigDecimal lineTotal() {
            return line != null ? line.lineTotal() : saved.lineTotal();
        }

        boolean vatable() {
            return line != null ? line.vatable() : saved.vatable();
        }

        /** " V" after a VATable item's price, like the receipt. */
        String vatFlag() {
            return vatable() ? " V" : "";
        }
    }

    public PosScreen(ScreenContext screen, SalesService pos, PaymentService payments, ProductLookup products) {
        this.screen = screen;
        this.pos = pos;
        this.payments = payments;
        this.products = products;
        startOrResumeSale();
        buildView();
        refresh(null);
    }

    public Parent getView() {
        return view;
    }

    /** Puts the cursor in the barcode box. Call after the screen is shown. */
    public void focusBarcode() {
        barcodeField.requestFocus();
    }

    /* ------------------------------ the sale ------------------------------ */

    /**
     * The same cashier coming back (e.g. after Esc) continues their open sale;
     * an open sale left by someone else is cancelled (nothing was saved).
     */
    private void startOrResumeSale() {
        String me = screen.user().getUserId();
        Optional<Sale> open = pos.currentSale();
        if (open.isPresent() && open.get().getCashierId().equals(me)) {
            return;
        }
        if (open.isPresent()) {
            System.out.println("[POS] cancelling an open sale left by " + open.get().getCashierId());
            pos.cancelSale();
        }
        pos.startSale(me);
    }

    private void addFromBarcode() {
        Entry entry = readEntry();
        if (entry == null) {
            return;
        }
        if (entry.text().isEmpty()) {
            showError("Scan or type a barcode. F1 searches for products without one.");
            return;
        }
        run(() -> {
            SaleLineItem line = pos.addItem(entry.text(), entry.quantity());
            barcodeField.clear();
            refresh(line.productId());
            showInfo("Added " + describe(line));
        }, () -> barcodeField.selectAll());
    }

    private void addFromSearch() {
        Entry entry = readEntry();
        if (entry == null) {
            return;
        }
        Optional<ProductInfo> picked;
        try {
            picked = ProductSearchDialog.pick(screen.stage(), products, entry.text());
        } catch (RuntimeException e) {
            showError(UiErrors.message(e, "Products could not be searched. Try again."));
            return;
        }
        if (picked.isEmpty()) {
            focusBarcode();
            return;
        }
        run(() -> {
            SaleLineItem line = pos.addProduct(picked.get().productId(), entry.quantity());
            barcodeField.clear();
            refresh(line.productId());
            showInfo("Added " + describe(line));
        }, null);
        focusBarcode();
    }

    private void changeQuantity(Row row, BigDecimal newQty) {
        run(() -> {
            SaleLineItem line = pos.updateQuantity(row.line().productId(), newQty);
            refresh(line.productId());
            showInfo(describe(line));
        }, null);
    }

    private void askQuantity() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            showError("Select a line first (Up / Down).");
            return;
        }
        Optional<BigDecimal> qty = QuantityDialog.ask(screen.stage(), row.line());
        qty.ifPresent(q -> changeQuantity(row, q));
        table.requestFocus();
    }

    private void stepQuantity(int step) {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        BigDecimal next = row.line().quantity().add(BigDecimal.valueOf(step));
        if (next.signum() <= 0) {
            showError("Press Delete to remove " + row.line().productName() + ".");
            return;
        }
        changeQuantity(row, next);
    }

    private void removeSelected() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        int index = table.getSelectionModel().getSelectedIndex();
        run(() -> {
            pos.removeItem(row.line().productId());
            refresh(null);
            if (!rows.isEmpty()) {
                table.getSelectionModel().select(Math.min(index, rows.size() - 1));
            }
            showInfo("Removed " + row.line().productName() + ".");
        }, null);
        if (rows.isEmpty()) {
            focusBarcode();
        }
    }

    /** The red Remove Item / Void Item button and the Delete key. */
    private void removeOrVoid() {
        if (retrieved != null) {
            voidItem();
        } else {
            removeSelected();
        }
    }

    /** The red Cancel Transaction / Void Transaction button and F9. */
    private void cancelOrVoid() {
        if (retrieved != null) {
            voidTransaction();
        } else {
            cancelSale();
        }
    }

    private void cancelSale() {
        Sale sale = pos.currentSale().orElse(null);
        if (sale == null || sale.isEmpty()) {
            showInfo("Nothing to cancel.");
            return;
        }
        int lines = sale.getItems().size();
        if (PosConfirm.ask(screen.stage(), "Cancel Transaction",
                "Cancel this transaction? All " + lines + (lines == 1 ? " line" : " lines")
                        + " will be removed. Nothing is saved.", "Cancel Transaction", "Keep Sale")) {
            pos.cancelSale();
            pos.startSale(screen.user().getUserId());
            refresh(null);
            showInfo("Transaction cancelled. Ready for the next customer.");
        }
        focusBarcode();
    }

    /** F5: open the payment card (only when the sale can be paid). */
    private void pay() {
        if (paymentCard != null) {
            return;
        }
        if (payButton.isDisable()) {
            showError(payHint.getText().isEmpty() ? "This sale can't be paid yet." : payHint.getText());
            return;
        }
        Sale sale = pos.currentSale().orElseThrow();
        paymentCard = PaymentOverlay.show(content, payments, sale, this::completePayment, () -> {
            paymentCard = null;
            focusBarcode();
        });
    }

    /** The payment card was completed: save the sale, then hand over the receipt. */
    private void completePayment(PaymentRecord payment) {
        SaleRecord done;
        try {
            done = pos.finalizeSale(payment);
        } catch (CartException | CheckoutException | InsufficientStockException e) {
            refresh(null);
            showError(e.getMessage() + " Nothing was saved.");
            focusBarcode();
            return;
        } catch (RuntimeException e) {
            refresh(null);
            showError(UiErrors.message(e, "The sale could not be saved. Nothing was saved; try Payment again."));
            focusBarcode();
            return;
        }
        // The sale is saved. Get the next one ready, then hand over the receipt.
        lastInvoice = done.invoiceNo();
        pos.startSale(screen.user().getUserId());
        refresh(null);
        showInfo("Sale " + done.invoiceNo() + " saved. Ready for the next customer.");
        Receipt receipt;
        try {
            receipt = screen.app().receipts().build(done);
        } catch (RuntimeException e) {
            System.err.println("[RECEIPT] " + e);
            showError("Sale " + done.invoiceNo() + " is saved, but its receipt could not be made. "
                    + "Press F6 to reprint it.");
            focusBarcode();
            return;
        }
        if (PosSettings.autoPrintReceipt()) {
            autoPrint(done, receipt);
        } else {
            try {
                if (SaleCompleteDialog.show(screen.stage(), done, receipt, screen.app().receiptPrinter(),
                        screen.app().config().receiptWidth())) {
                    checkPrintedLater(done.invoiceNo());
                }
            } catch (RuntimeException e) {
                System.err.println("[RECEIPT] " + e);
                showError("Sale " + done.invoiceNo() + " is saved, but its receipt could not be shown. "
                        + "Press F6 to reprint it.");
            }
        }
        focusBarcode();
    }

    /**
     * POS Settings "auto print" is on: print the new sale's receipt with no preview. The change to
     * hand back goes in the status bar. If printing fails, a small notification asks the cashier to
     * check the printer; the sale itself is already saved.
     *
     * Windows takes the job into its queue even when the printer is unplugged or off, so a print
     * that "succeeds" is checked again {@link #PRINT_CHECK_DELAY} later: if the printer is then
     * stopped, offline or still has jobs waiting (as System Status shows), the same notification
     * appears. The check runs in the background, so the cashier can keep scanning.
     */
    private void autoPrint(SaleRecord done, Receipt receipt) {
        PaymentRecord p = done.payments().get(0);
        String change = p.method() == PaymentMethod.CASH ? "Change " + peso(p.change()) + ". " : "";
        try {
            screen.app().receiptPrinter().printReceipt(receipt);
            showInfo("Sale " + done.invoiceNo() + " saved. " + change + "Receipt sent to the printer.");
        } catch (ReceiptPrintException | RuntimeException e) {
            System.err.println("[PRINT] " + e);
            showInfo("Sale " + done.invoiceNo() + " saved. " + change);
            receiptNotPrinted(done.invoiceNo());
            return;
        }
        checkPrintedLater(done.invoiceNo());
    }

    /**
     * After a receipt was handed to Windows (auto-print, the Sale Complete preview or Reprint):
     * {@link #PRINT_CHECK_DELAY} later, check the receipt printer in the background, and show the
     * "Receipt not printed" notification if it is stopped, offline or still has jobs waiting.
     * Windows queues the job even when the printer is unplugged, so the print call alone can't tell.
     */
    private void checkPrintedLater(String invoiceNo) {
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(PRINT_CHECK_DELAY);
        wait.setOnFinished(ev -> {
            Thread check = new Thread(() -> {
                String trouble;
                try {
                    trouble = shared.PrinterSettings.trouble(shared.PrinterSettings.Use.RECEIPT);
                } catch (RuntimeException e) {
                    System.err.println("[PRINT] could not check the printer: " + e);
                    return;   // can't tell: say nothing rather than guess
                }
                if (trouble != null) {
                    System.err.println("[PRINT] after " + invoiceNo + ": " + trouble);
                    javafx.application.Platform.runLater(() -> receiptNotPrinted(invoiceNo));
                }
            }, "receipt-print-check");
            check.setDaemon(true);
            check.start();
        });
        wait.play();
    }

    /** How long after printing the printer is checked (a receipt normally prints well within this). */
    private static final javafx.util.Duration PRINT_CHECK_DELAY = javafx.util.Duration.seconds(5);

    /** The "Receipt not printed" notification (no red status bar line: the notification is enough). */
    private void receiptNotPrinted(String invoiceNo) {
        PosToast.warn(content, "Receipt not printed",
                "Check that the printer is plugged in, turned on and has paper. The receipt will print "
                        + "as soon as the printer is detected. If it doesn't, use Reprint Receipt (F6) "
                        + "for invoice " + invoiceNo + ".");
    }

    /** Menu > POS Settings. */
    private void openSettings() {
        if (PosSettingsDialog.show(screen.stage())) {
            showInfo(PosSettings.autoPrintReceipt()
                    ? "Settings saved. Receipts now print automatically after payment."
                    : "Settings saved. The receipt preview shows after payment.");
        }
        if (retrieved != null) {
            table.requestFocus();   // the barcode box is locked while a sale is retrieved
        } else {
            focusBarcode();
        }
    }

    /**
     * F6: show / print a receipt again. While a sale is retrieved (F7) it opens that sale's receipt
     * straight away; otherwise it asks for the invoice number first.
     */
    private void reprint() {
        if (retrieved != null) {
            showReprint(retrieved);
            table.requestFocus();   // the barcode box is locked while a sale is retrieved
            return;
        }
        Optional<SaleRecord> sale = ReprintDialog.ask(screen.stage(), screen.app().saleHistory(), lastInvoice);
        sale.ifPresent(this::showReprint);
        focusBarcode();
    }

    /** The Reprint Receipt modal for one saved sale. */
    private void showReprint(SaleRecord sale) {
        try {
            Receipt receipt = screen.app().receipts().buildReprint(sale);
            boolean printed = ReceiptDialog.show(screen.stage(), "Reprint Receipt", "Invoice "
                    + sale.invoiceNo(), null, receipt, screen.app().receiptPrinter(),
                    screen.app().config().receiptWidth());
            if (printed) {
                showInfo("Sent the receipt for " + sale.invoiceNo() + " to the printer.");
                checkPrintedLater(sale.invoiceNo());
            }
        } catch (RuntimeException e) {
            System.err.println("[RECEIPT] " + e);
            showError("The receipt could not be shown. Try again.");
        }
    }

    /* ------------------------------ display ------------------------------- */

    /** Re-reads the sale, its stock problems and totals; keeps or moves the selection. */
    private void refresh(String selectProductId) {
        if (retrieved != null) {
            return;   // a retrieved sale is on screen; the open sale is shown again after F8
        }
        Row selected = table.getSelectionModel().getSelectedItem();
        String keep = selectProductId != null ? selectProductId
                : selected == null || selected.line() == null ? null : selected.line().productId();

        Sale sale = pos.currentSale().orElseThrow();
        Map<String, StockProblem> problems = new HashMap<>();
        boolean canPay;
        String hint = "";
        try {
            for (StockProblem p : pos.findStockProblems()) {
                problems.put(p.productId(), p);
            }
            canPay = pos.canCheckout();
            if (sale.isEmpty()) {
                hint = "Scan an item to start.";
            } else if (!problems.isEmpty()) {
                StockProblem first = problems.values().iterator().next();
                hint = first.message() + (problems.size() > 1
                        ? " (" + (problems.size() - 1) + " more line(s) in red.)" : "");
            }
        } catch (RuntimeException e) {
            canPay = false;
            hint = UiErrors.message(e, "Stock could not be checked.");
        }

        List<SaleLineItem> items = sale.getItems();
        rows.clear();
        for (int i = 0; i < items.size(); i++) {
            String id = items.get(i).productId();
            rows.add(new Row(i + 1, items.get(i), problems.get(id), sale.isPriceEdited(id)));
        }
        if (keep != null) {
            for (Row r : rows) {
                if (r.line() != null && r.line().productId().equals(keep)) {
                    table.getSelectionModel().select(r);
                    table.scrollTo(r);
                }
            }
        }

        SaleTotals t = sale.totals();
        subtotalValue.setText(peso(t.subtotal()));
        showVat(t.vatableSales(), t.vatAmount(), t.vatExemptSales());
        totalValue.setText(peso(t.total()));
        BigDecimal pieces = BigDecimal.ZERO;
        for (SaleLineItem line : items) {
            pieces = pieces.add(line.quantity());
        }
        itemCount.setText(Quantity.format(pieces));
        lock(payButton, !canPay);
        lock(newTransactionButton, sale.isEmpty());   // empty cart = already ready for a new sale
        payHint.setText(hint);
    }

    private void showError(String text) {
        setMessage(text, "-fx-font-weight: bold; -fx-text-fill: " + RED + ";");
    }

    private void showInfo(String text) {
        setMessage(text, "-fx-text-fill: " + GREEN + ";");
    }

    /** For features that are not built yet. */
    private void showNotice(String text) {
        setMessage(text, "-fx-text-fill: " + DARK + ";");
    }

    /** The message sits in the status bar; the full text is in a tooltip in case it is cut short. */
    private void setMessage(String text, String style) {
        message.setText(text);
        message.setStyle("-fx-font-size: 20px; " + style);
        messageTip.setText(text);
        message.setTooltip(text == null || text.isEmpty() ? null : messageTip);
    }

    /** What was typed in the barcode box: how many, and the barcode (or search text). */
    record Entry(BigDecimal quantity, String text) { }

    /**
     * The barcode box split into quantity and text: "4800000000019" is one piece, "5*4800000000019"
     * is five. Null (with a message) if the quantity is not a number above zero.
     */
    private Entry readEntry() {
        String typed = barcodeField.getText() == null ? "" : barcodeField.getText().trim();
        Matcher m = QUANTITY_PREFIX.matcher(typed);
        if (!m.matches()) {
            return new Entry(Quantity.ONE, typed);
        }
        BigDecimal qty;
        try {
            qty = Quantity.of(m.group(1));
        } catch (RuntimeException e) {
            showError("Quantity must be a number, e.g. 5*4800000000019 or 1.5*4800000000040.");
            return null;
        }
        if (qty.signum() <= 0) {
            showError("Quantity must be more than zero.");
            return null;
        }
        return new Entry(qty, m.group(2).trim());
    }

    /** Runs a cart change and turns its errors into messages. */
    private void run(Action action, Runnable onError) {
        try {
            action.run();
            return;
        } catch (CartException | InsufficientStockException e) {
            showError(e.getMessage());
        } catch (RuntimeException e) {
            showError(UiErrors.message(e, "Something went wrong. Try again."));
        }
        if (onError != null) {
            onError.run();
        }
    }

    interface Action {
        void run() throws CartException, InsufficientStockException;
    }

    static String peso(BigDecimal amount) {
        return PESO + Money.format(amount);
    }

    static String describe(SaleLineItem line) {
        return line.productName() + " - " + Quantity.format(line.quantity()) + " " + line.unit()
                + " @ " + peso(line.unitPrice()) + (line.priceType() == PriceType.WHOLESALE ? " (wholesale)" : "")
                + " = " + peso(line.lineTotal());
    }

    /* --------------------------- skeleton features --------------------------- */

    /** Logout (top right): ends the session and goes back to the login screen. */
    private void logout() {
        Sale sale = pos.currentSale().orElse(null);
        if (sale != null && !sale.isEmpty()) {
            int lines = sale.getItems().size();
            if (!PosConfirm.ask(screen.stage(), "Log Out",
                    "Log out? This sale has " + lines + (lines == 1 ? " line" : " lines")
                            + "; they will be discarded. Nothing is saved.", "Log Out", "Stay")) {
                focusBarcode();
                return;
            }
            pos.cancelSale();
        }
        try {
            screen.app().auth().logout(screen.sessionId());
        } finally {
            screen.goHome();   // the session has ended, so the router shows the login screen
        }
    }

    /**
     * F2: sell something that is not in the catalog (a side product, a one-off). Asks for a name,
     * price, quantity and VAT; the item is only part of this sale and is never added to the
     * Product table or stock (see SalesService.addOpenItem). A quantity typed in the barcode box
     * first ("3*") is used as the starting quantity.
     */
    private void openItem() {
        Entry entry = readEntry();
        if (entry == null) {
            return;
        }
        SaleLineItem[] added = {null};
        boolean ok = OpenItemDialog.show(screen.stage(), entry.quantity(), typed -> {
            try {
                added[0] = pos.addOpenItem(typed.name(), typed.unit(), typed.unitPrice(), typed.quantity(), typed.vatable());
                return null;
            } catch (CartException e) {
                return e.getMessage();
            } catch (RuntimeException e) {
                return UiErrors.message(e, "The item could not be added. Try again.");
            }
        });
        if (ok) {
            barcodeField.clear();
            refresh(added[0].productId());
            showInfo("Added open item " + describe(added[0]));
        }
        focusBarcode();
    }

    /**
     * F4: change the selected line's unit price for this sale only (any cashier may). The product's
     * price in the catalog is not changed; the line shows "(PRICE EDITED)" and the edit is written to
     * the audit log when the sale is completed (see SalesService.editPrice).
     */
    private void editPrice() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null || row.line() == null) {
            showError("Select a line first (Up / Down).");
            return;
        }
        SaleLineItem line = row.line();
        SaleLineItem[] changed = {null};
        boolean ok = EditPriceDialog.show(screen.stage(), line, price -> {
            try {
                changed[0] = pos.editPrice(line.productId(), price);
                return null;
            } catch (CartException e) {
                return e.getMessage();
            } catch (RuntimeException e) {
                return UiErrors.message(e, "The price could not be changed. Try again.");
            }
        });
        if (ok) {
            refresh(changed[0].productId());
            showInfo("Price edited: " + describe(changed[0]));
        }
        table.requestFocus();
    }

    /**
     * F7: find a completed sale by invoice number and show it in the cart, read only. The barcode box
     * and every button are locked except Reprint Receipt (F6, reprints this sale), Void Item,
     * Void Transaction and New Transaction (F8), which goes back. The open sale is left as it was and comes back with New Transaction.
     */
    private void retrieveTransaction() {
        if (retrieved != null) {
            return;
        }
        Optional<SaleRecord> sale;
        try {
            sale = ReprintDialog.ask(screen.stage(), screen.app().saleHistory(), lastInvoice,
                    "Retrieve Transaction", "Type the invoice number of the completed sale.", "Retrieve");
        } catch (RuntimeException e) {
            showError(UiErrors.message(e, "Sales could not be searched. Try again."));
            return;
        }
        if (sale.isEmpty()) {
            focusBarcode();
            return;
        }
        showRetrieved(sale.get());
    }

    /** Shows a completed sale in the cart table and locks the screen (see {@link #retrieveTransaction}). */
    private void showRetrieved(SaleRecord sale) {
        retrieved = sale;   // lastInvoice is NOT changed: F6 / F7 keep offering the latest completed sale
        rows.clear();
        BigDecimal pieces = BigDecimal.ZERO;
        for (SaleLineRecord line : sale.lines()) {
            rows.add(Row.of(line));
            pieces = pieces.add(line.quantity());
        }
        itemCount.setText(Quantity.format(pieces));
        subtotalValue.setText(peso(sale.subtotal()));
        showVat(sale.vatableSales(), sale.vatAmount(), sale.vatExemptSales());
        totalValue.setText(peso(sale.total()));
        summaryTitle.setText("RETRIEVED SALE");
        payHint.setText("Invoice " + sale.invoiceNo() + "  \u00B7  "
                + RETRIEVED_TIME.format(shared.PhTime.of(sale.completedAt())));
        payHint.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");
        applyMode();
        if (!rows.isEmpty()) {
            table.getSelectionModel().select(0);
        }
        table.requestFocus();
        showNotice("Showing sale " + sale.invoiceNo() + ". Press New Transaction (F8) to go back.");
    }

    /** F8 while a sale is retrieved: back to the open sale (as it was before F7). */
    private void leaveRetrieved() {
        retrieved = null;
        summaryTitle.setText("ORDER SUMMARY");
        payHint.setStyle("-fx-font-size: 16px; -fx-text-fill: " + RED + ";");
        applyMode();
        refresh(null);
        showNotice("Back to the current transaction.");
        focusBarcode();
    }

    /** Locks or unlocks the screen for a retrieved sale, and relabels Remove / Cancel as Void. */
    private void applyMode() {
        boolean locked = retrieved != null;
        lock(barcodeBox, locked);
        barcodeField.setEditable(!locked);
        for (Button b : lockedWhileRetrieved) {
            lock(b, locked);
        }
        lock(payButton, locked);
        if (locked) {
            lock(newTransactionButton, false);   // F8 is the way back from a retrieved sale
        }   // when unlocking, refresh() sets it from the cart
        relabel(removeButton, locked ? "Void Item" : "Remove Item", "Del", 22);
        relabel(cancelButton, locked ? "Void Transaction" : "Cancel Transaction", "F9", 26);
    }

    /** Disables a control and dims it a little (not as faint as JavaFX's default), or the reverse. */
    private static void lock(javafx.scene.Node node, boolean locked) {
        node.setDisable(locked);
        node.setOpacity(locked ? LOCKED_OPACITY : 1);
    }

    /** Del while a sale is retrieved. TODO: void one line of a completed sale (decide who may do it). */
    private void voidItem() {
        showNotice("Void Item is not available yet.");
    }

    /** F9 while a sale is retrieved. TODO: void the whole completed sale (decide who may do it). */
    private void voidTransaction() {
        showNotice("Void Transaction is not available yet.");
    }

    /**
     * F8: leaves a retrieved sale. TODO(skeleton): otherwise, start a new transaction (for example,
     * put this one on hold and begin another).
     */
    private void newTransaction() {
        if (retrieved != null) {
            leaveRetrieved();
            return;
        }
        if (newTransactionButton.isDisable()) {
            showNotice("Already ready for a new sale. Scan an item to start.");
            return;
        }
        showNotice("New Transaction is not available yet.");
    }

    /**
     * Hamburger menu (top right): a small dropdown styled like the sidebar on the Products screen
     * (same colours and list rows) with Dashboard, POS Settings and Logout.
     */
    private void showMenu(Node anchor) {
        Popup popup = new Popup();
        popup.setAutoHide(true);

        Button dashboard = new Button("Dashboard");
        dashboard.setOnAction(e -> {
            popup.hide();
            screen.goHome();
        });
        Button settings = new Button("POS Settings");
        settings.setOnAction(e -> {
            popup.hide();
            openSettings();
        });
        Button logout = new Button("Logout");
        logout.setOnAction(e -> {
            popup.hide();
            logout();
        });
        for (Button item : List.of(dashboard, settings, logout)) {
            item.setMaxWidth(Double.MAX_VALUE);
            item.setFocusTraversable(false);
            Styles.navItem(item, false);
        }

        VBox menu = new VBox(dashboard, settings, logout);
        menu.setMinWidth(MENU_WIDTH);
        menu.setPrefWidth(MENU_WIDTH);
        menu.setMaxWidth(MENU_WIDTH);
        menu.setStyle("-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 6; "
                + "-fx-border-color: " + Theme.BORDER + "; -fx-border-radius: 6;");
        menu.setEffect(new DropShadow(12, Color.rgb(0, 0, 0, 0.25)));
        menu.setPadding(new Insets(4, 0, 4, 0));
        popup.getContent().add(menu);

        // right edge of the menu lines up with the right edge of the hamburger button
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMaxX() - MENU_WIDTH, b.getMaxY() + 4);
    }

    /* ------------------------------- layout ------------------------------- */

    private void buildView() {
        PosStyle.root(content);
        message.setId("pos-message");
        barcodeField.setId("pos-barcode");
        totalValue.setId("pos-total");
        payHint.setId("pos-hint");
        itemCount.setId("pos-items");

        BorderPane main = new BorderPane();
        main.setTop(buildStatusBar());
        main.setCenter(buildBody());
        content.getChildren().add(main);

        // --- screen-wide keys ---
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (paymentCard != null) {
                paymentCard.handleKey(e);   // while the payment card is open it gets every key
                return;
            }
            KeyCode k = e.getCode();
            if (retrieved != null) {
                handleRetrievedKey(e);   // only F8, F9, Delete, Esc and the arrow keys work
                return;
            }
            if (k == KeyCode.F1) {
                addFromSearch();
            } else if (k == KeyCode.F2) {
                openItem();
            } else if (k == KeyCode.F3) {
                askQuantity();
            } else if (k == KeyCode.F4) {
                editPrice();
            } else if (k == KeyCode.F5) {
                pay();
            } else if (k == KeyCode.F6) {
                reprint();
            } else if (k == KeyCode.F7) {
                retrieveTransaction();
            } else if (k == KeyCode.F8) {
                newTransaction();
            } else if (k == KeyCode.F9) {
                cancelSale();
            } else if (k == KeyCode.ESCAPE) {
                screen.goHome();
            } else if ((k == KeyCode.UP || k == KeyCode.DOWN) && !table.isFocused() && !rows.isEmpty()) {
                // arrow keys from the barcode box move into the cart
                table.requestFocus();
                if (table.getSelectionModel().isEmpty()) {
                    table.getSelectionModel().select(k == KeyCode.UP ? rows.size() - 1 : 0);
                }
            } else {
                return;
            }
            e.consume();
        });
    }

    /** Keys while a retrieved sale is shown: F6 reprint it, F8 back, F9 void transaction, Delete void item, Esc home. */
    private void handleRetrievedKey(KeyEvent e) {
        switch (e.getCode()) {
            case F6 -> reprint();
            case F8 -> newTransaction();
            case F9 -> voidTransaction();
            case DELETE, BACK_SPACE -> voidItem();
            case ESCAPE -> screen.goHome();
            case UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END, TAB -> {
                if (!table.isFocused()) {
                    table.requestFocus();
                }
                return;   // let the table move the selection
            }
            default -> { }
        }
        e.consume();   // every other key (F1-F5, F7, Enter, +, -, typing) does nothing
    }

    /** Online - Cashier: name - [message] - menu (Dashboard, Logout). */
    private Node buildStatusBar() {
        // TODO(skeleton): "Online" is always shown. Make it follow the real connection (database / server) status.
        Circle dot = new Circle(10, Color.web(ONLINE_GREEN));
        Label online = label("Online", 22, false);
        Label cashier = label("Cashier: " + screen.user().getFullName(), 22, false);

        message.setMaxWidth(Double.MAX_VALUE);
        message.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(message, Priority.ALWAYS);
        message.setPadding(new Insets(0, 24, 0, 40));

        Button menu = new Button();
        menu.setGraphic(hamburgerIcon());
        PosStyle.plainButton(menu, "");
        menu.setFocusTraversable(false);
        menu.setPadding(new Insets(8));
        menu.setOnAction(e -> showMenu(menu));

        HBox bar = new HBox(12, dot, online, spacer(24), cashier, message, menu);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 14, 0, 18));
        fixHeight(bar, 46);
        bar.setStyle("-fx-background-color: " + STATUS_BAR + ";");
        return bar;
    }

    /** Left: cart table and the entry row. Right: order summary and the action buttons. */
    private Node buildBody() {
        VBox left = new VBox(20, buildTable(), buildEntryRow());
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox.setHgrow(left, Priority.ALWAYS);

        Node summary = buildSummary();
        VBox.setVgrow(summary, Priority.ALWAYS);
        Button reprint = keyButton("Reprint Receipt", "F6", PosStyle.Kind.SECONDARY, DARK, 26, this::reprint);
        Button retrieve = keyButton("Retrieve Transaction", "F7", PosStyle.Kind.SECONDARY, DARK, 26, this::retrieveTransaction);
        Button newTransaction = keyButton("New Transaction", "F8", PosStyle.Kind.SECONDARY, DARK, 26, this::newTransaction);
        Button cancel = keyButton("Cancel Transaction", "F9", PosStyle.Kind.DANGER, "white", 26, this::cancelOrVoid);
        cancelButton = cancel;
        newTransactionButton = newTransaction;
        lockedWhileRetrieved.add(retrieve);   // Reprint stays on: it reprints the retrieved sale
        for (Button b : List.of(reprint, retrieve, newTransaction, cancel)) {
            fixHeight(b, 70);
            b.setMaxWidth(Double.MAX_VALUE);
        }
        VBox right = new VBox(19, summary, reprint, retrieve, newTransaction, cancel);
        right.setMinWidth(507);
        right.setPrefWidth(507);
        right.setMaxWidth(507);

        HBox body = new HBox(19, left, right);
        body.setPadding(new Insets(19, 20, 19, 23));
        return body;
    }

    private Node buildTable() {
        table.setItems(rows);
        PosStyle.table(table);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label empty = new Label("No items yet. Scan a barcode to start.");
        PosStyle.placeholder(empty);
        table.setPlaceholder(empty);
        table.getColumns().add(column("#", 73, r -> String.valueOf(r.no())));
        table.getColumns().add(column("Item", 503, Row::itemLabel));
        table.getColumns().add(column("Qty", 99, r -> Quantity.format(r.quantity()) + " " + r.unit()));
        table.getColumns().add(column("Unit Price", 188, r -> Money.format(r.unitPrice()) + r.vatFlag()));
        table.getColumns().add(column("Price", 218,
                r -> r.priceType() == PriceType.WHOLESALE ? "WHOLESALE" : "RETAIL"));
        table.getColumns().add(column("Amount", 270, r -> Money.format(r.lineTotal()) + r.vatFlag()));
        // A line with a stock problem is tinted red; the sentence is in the summary card above Payment.
        table.setRowFactory(t -> new PosStyle.CartRow<Row>(row -> row.problem() != null));
        table.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case ENTER -> { askQuantity(); e.consume(); }
                case DELETE, BACK_SPACE -> { removeSelected(); e.consume(); }
                case ADD, PLUS -> { stepQuantity(1); e.consume(); }
                case SUBTRACT, MINUS -> { stepQuantity(-1); e.consume(); }
                default -> {
                    // typing digits in the cart goes back to the barcode box (scanner while table has focus)
                    if (e.getCode().isDigitKey() || e.getCode().isLetterKey()) {
                        focusBarcode();
                    }
                }
            }
        });
        return table;
    }

    private static TableColumn<Row, String> column(String name, double width, Function<Row, String> value) {
        TableColumn<Row, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setReorderable(false);
        c.setCellFactory(col -> new PosStyle.GridCell<Row>());
        return c;
    }

    /** Barcode box, scan button, Search Product, Open Item, Edit Qty, Edit Price, Remove Item. */
    private Node buildEntryRow() {
        Label title = label("Scan or Enter Barcode", 20, true);
        barcodeField.setAccessibleText("Barcode");
        barcodeField.setTooltip(new Tooltip("Scan, or type a barcode and press Enter.\nTo add several: 5*4800000000019"));
        barcodeField.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-font-size: 22px; -fx-font-weight: bold; "
                + "-fx-text-fill: " + DARK + "; -fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
        barcodeField.setOnAction(e -> addFromBarcode());
        VBox text = new VBox(0, title, barcodeField);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        barcodeBox.getChildren().add(text);
        barcodeBox.setAlignment(Pos.CENTER_LEFT);
        barcodeBox.setPadding(new Insets(0, 14, 0, 14));
        barcodeBox.setMinWidth(300);
        barcodeBox.setPrefWidth(372);
        HBox.setHgrow(barcodeBox, Priority.ALWAYS);
        fixHeight(barcodeBox, 70);
        barcodeBox.setStyle(barcodeBoxStyle(false));
        barcodeField.focusedProperty().addListener((o, was, now) -> barcodeBox.setStyle(barcodeBoxStyle(now)));

        Button scan = iconKeyButton(barcodeIcon(), "Enter", this::addFromBarcode);
        fixSize(scan, 65, 70);

        Button search = new Button();
        HBox searchRow = new HBox(6, label("Search Product", 15, true), searchIcon());
        searchRow.setAlignment(Pos.CENTER);
        search.setGraphic(keyed(searchRow, "F1", DARK));
        decorate(search, PosStyle.Kind.SECONDARY, this::addFromSearch);
        fixSize(search, 166, 70);

        Button openItem = keyButton("Open Item", "F2", PosStyle.Kind.SECONDARY, DARK, 26, this::openItem);
        Button editQty = keyButton("Edit Qty", "F3", PosStyle.Kind.SECONDARY, DARK, 26, this::askQuantity);
        Button editPrice = keyButton("Edit Price", "F4", PosStyle.Kind.SECONDARY, DARK, 26, this::editPrice);
        Button remove = keyButton("Remove Item", "Del", PosStyle.Kind.DANGER, "white", 22, this::removeOrVoid);
        removeButton = remove;
        lockedWhileRetrieved.addAll(List.of(scan, search, openItem, editQty, editPrice));
        fixSize(openItem, 165, 70);
        fixSize(editQty, 164, 70);
        fixSize(editPrice, 165, 70);
        fixSize(remove, 175, 70);

        HBox row = new HBox(11, barcodeBox, scan, search, openItem, editQty, editPrice, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String barcodeBoxStyle(boolean focused) {
        return "-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 6; -fx-border-radius: 6; "
                + "-fx-border-width: 2; -fx-border-color: " + (focused ? Theme.PRIMARY_BLUE : "transparent") + ";";
    }

    /** ORDER SUMMARY: total items, subtotal, VAT, TOTAL, a stock hint and the PAYMENT button. */
    private Node buildSummary() {
        Label title = summaryTitle;
        title.setStyle("-fx-font-size: 40px; -fx-text-fill: " + DARK + "; -fx-font-weight: bold;");
        title.setPadding(new Insets(24, 0, 19, 0));

        VBox items = new VBox(sumRow("Total Items", itemCount, false));
        items.setAlignment(Pos.CENTER_LEFT);
        fixHeight(items, 78);

        // Subtotal, then the VAT breakdown (as on the receipt): prices include VAT, so these add up to the TOTAL.
        VBox breakdown = new VBox(4, vatRow("VATable", vatableValue), vatRow("VAT", vatValue),
                vatRow("VAT-Exempt", exemptValue), vatRow("VAT-Zero", zeroRatedValue));
        breakdown.setPadding(new Insets(6, 0, 0, 0));
        VBox amounts = new VBox(6, sumRow("Subtotal", subtotalValue, false), breakdown);
        amounts.setPadding(new Insets(20, 0, 20, 0));

        VBox total = new VBox(sumRow("TOTAL", totalValue, true));
        total.setPadding(new Insets(22, 0, 0, 0));

        payHint.setWrapText(true);
        payHint.setStyle("-fx-font-size: 16px; -fx-text-fill: " + RED + ";");
        payButton = keyButton("PAYMENT", "F5", PosStyle.Kind.PRIMARY, "white", 32, this::pay);
        payButton.setMaxWidth(Double.MAX_VALUE);
        fixHeight(payButton, 68);
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        VBox card = new VBox(title, divider(), items, divider(), amounts, divider(), total, grow, payHint, gap(8), payButton);
        card.setPadding(new Insets(0, 19, 24, 19));
        card.setStyle("-fx-background-color: white; -fx-border-color: #E3E7ED; -fx-border-width: 2;");
        return card;
    }

    /** VATable / VAT / VAT-Exempt / VAT-Zero (VAT-Zero is always 0.00: no product is zero-rated yet). */
    private void showVat(BigDecimal vatable, BigDecimal vat, BigDecimal exempt) {
        vatableValue.setText(peso(vatable));
        vatValue.setText(peso(vat));
        exemptValue.setText(peso(exempt));
        zeroRatedValue.setText(peso(Money.ZERO));
    }

    /** A smaller grey row of the VAT breakdown, indented under Subtotal. */
    private static Node vatRow(String name, Label value) {
        String style = "-fx-font-size: 20px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";";
        Label label = new Label(name);
        label.setStyle(style);
        value.setStyle(style);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(label, grow, value);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 6, 0, 24));
        return row;
    }

    private static Node sumRow(String name, Label value, boolean strong) {
        Label label = label(name, 24, strong);
        value.setStyle("-fx-font-size: 24px; -fx-text-fill: " + DARK + ";" + (strong ? " -fx-font-weight: bold;" : ""));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(label, grow, value);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 6, 0, 4));
        return row;
    }

    /* ------------------------------- widgets ------------------------------- */

    private static Label label(String text, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: " + size + "px; -fx-text-fill: " + DARK + ";" + (bold ? " -fx-font-weight: bold;" : ""));
        return l;
    }

    private static Region spacer(double width) {
        Region r = new Region();
        r.setMinWidth(width);
        r.setPrefWidth(width);
        return r;
    }

    private static Region gap(double height) {
        Region r = new Region();
        fixHeight(r, height);
        return r;
    }

    private static Region divider() {
        Region r = new Region();
        fixHeight(r, 1);
        r.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return r;
    }

    private static void fixHeight(Region r, double height) {
        r.setMinHeight(height);
        r.setPrefHeight(height);
        r.setMaxHeight(height);
    }

    private static void fixSize(Region r, double width, double height) {
        r.setMinSize(width, height);
        r.setPrefSize(width, height);
        r.setMaxSize(width, height);
    }

    /** A button with its name and the key underneath, e.g. "Open Item" over "[F2]". */
    private static Button keyButton(String name, String key, PosStyle.Kind kind, String color, int size, Runnable onClick) {
        Label main = new Label(name);
        main.setStyle("-fx-font-size: " + size + "px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        Button b = new Button();
        // the label's own line height leaves a gap under the text, so pull the [key] hint up under it
        b.setGraphic(keyed(main, key, color, -Math.round(size / 5.0)));
        decorate(b, kind, onClick);
        return b;
    }

    /** Changes the text of a red {@link #keyButton} (e.g. "Remove Item" to "Void Item"); its action stays. */
    private static void relabel(Button b, String name, String key, int size) {
        Label main = new Label(name);
        main.setStyle("-fx-font-size: " + size + "px; -fx-font-weight: bold; -fx-text-fill: white;");
        b.setGraphic(keyed(main, key, "white", -Math.round(size / 5.0)));
        b.setAccessibleText(name);
    }

    private static Button iconKeyButton(Node icon, String key, Runnable onClick) {
        Button b = new Button();
        b.setGraphic(keyed(icon, key, DARK));
        decorate(b, PosStyle.Kind.SECONDARY, onClick);
        return b;
    }

    private static Node keyed(Node main, String key, String color) {
        return keyed(main, key, color, 2);
    }

    private static Node keyed(Node main, String key, String color, double gap) {
        Label hint = new Label("[" + key + "]");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + "; -fx-padding: 0;");
        VBox box = new VBox(gap, main, hint);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private static void decorate(Button b, PosStyle.Kind kind, Runnable onClick) {
        PosStyle.button(b, kind);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setFocusTraversable(false);   // the keyboard stays in the barcode box and the cart
        b.setOnAction(e -> onClick.run());
    }

    private static Node hamburgerIcon() {
        VBox lines = new VBox(5);
        for (int i = 0; i < 3; i++) {
            Rectangle bar = new Rectangle(26, 3, Color.web(DARK));
            bar.setArcWidth(3);
            bar.setArcHeight(3);
            lines.getChildren().add(bar);
        }
        return lines;
    }

    private static Node barcodeIcon() {
        double[] widths = {1, 2, 1, 2, 2, 1, 2, 1, 2, 1, 2, 2, 1};
        HBox bars = new HBox(1);
        bars.setAlignment(Pos.CENTER);
        for (double w : widths) {
            bars.getChildren().add(new Rectangle(w, 30, Color.web(DARK)));
        }
        return bars;
    }

    private static Node searchIcon() {
        Circle lens = new Circle(12, 12, 10);
        lens.setFill(Color.TRANSPARENT);
        lens.setStroke(Color.web(DARK));
        lens.setStrokeWidth(3);
        Line handle = new Line(20, 20, 30, 30);
        handle.setStroke(Color.web(DARK));
        handle.setStrokeWidth(4);
        handle.setStrokeLineCap(StrokeLineCap.ROUND);
        StackPane icon = new StackPane(new javafx.scene.Group(lens, handle));
        icon.setMinSize(32, 32);
        icon.setPrefSize(32, 32);
        icon.setMaxSize(32, 32);
        return icon;
    }
}