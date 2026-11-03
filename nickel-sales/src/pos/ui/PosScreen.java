package pos.ui;

import app.HomeScreen;
import app.ScreenContext;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
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
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import inventory.contract.InsufficientStockException;
import pos.cart.CartException;
import pos.cart.Sale;
import pos.cart.SaleLineItem;
import pos.cart.SaleTotals;
import pos.cart.SalesService;
import pos.cart.StockProblem;
import pos.checkout.CheckoutException;
import pos.checkout.PaymentService;
import pos.contract.PaymentRecord;
import pos.contract.PriceType;
import pos.contract.SaleRecord;
import pos.receipt.Receipt;
import product.contract.ProductInfo;
import product.contract.ProductLookup;
import shared.Money;
import shared.Quantity;
import shared.ScaledPane;
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
 * | F2           | Open Item                                  (NOT BUILT YET)     |
 * | F3 / Enter   | (cart) change the selected line's quantity                     |
 * | F4           | Edit Price                                 (NOT BUILT YET)     |
 * | + / -        | (cart) one more / one less of the selected line                |
 * | Delete       | (cart) remove the selected line                                |
 * | Up / Down    | move in the cart (from the barcode box too)                    |
 * | F5           | pay (only when the sale can be paid)                           |
 * | F6           | reprint a receipt by invoice number                            |
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
    private final Label totalValue = new Label();
    private final Label payHint = new Label();
    private Button payButton;
    private PaymentOverlay paymentCard;   // non-null while the payment card is open
    private String lastInvoice;           // filled in for F6 reprint

    /** One line in the cart table, with its stock problem (null if none). */
    record Row(int no, SaleLineItem line, StockProblem problem) { }

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

    private void cancelSale() {
        Sale sale = pos.currentSale().orElse(null);
        if (sale == null || sale.isEmpty()) {
            showInfo("Nothing to cancel.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Cancel this transaction? All " + sale.getItems().size() + " line(s) will be removed. Nothing is saved.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Cancel transaction");
        confirm.setHeaderText(null);
        confirm.initOwner(screen.stage());
        confirm.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
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
        try {
            Receipt receipt = screen.app().receipts().build(done);
            SaleCompleteDialog.show(screen.stage(), done, receipt, screen.app().receiptPrinter(),
                    screen.app().config().receiptWidth());
        } catch (RuntimeException e) {
            System.err.println("[RECEIPT] " + e);
            showError("Sale " + done.invoiceNo() + " is saved, but its receipt could not be shown. "
                    + "Press F6 to reprint it.");
        }
        focusBarcode();
    }

    /** F6: find a saved sale by invoice number and show / print its receipt again. */
    private void reprint() {
        Optional<SaleRecord> sale = ReprintDialog.ask(screen.stage(), screen.app().saleHistory(), lastInvoice);
        if (sale.isPresent()) {
            try {
                Receipt receipt = screen.app().receipts().buildReprint(sale.get());
                boolean printed = ReceiptDialog.show(screen.stage(), "Reprint", "Reprint  ·  Invoice "
                        + sale.get().invoiceNo(), null, receipt, screen.app().receiptPrinter(),
                        screen.app().config().receiptWidth());
                if (printed) {
                    showInfo("Reprinted the receipt for " + sale.get().invoiceNo() + ".");
                }
            } catch (RuntimeException e) {
                System.err.println("[RECEIPT] " + e);
                showError("The receipt could not be shown. Try again.");
            }
        }
        focusBarcode();
    }

    /* ------------------------------ display ------------------------------- */

    /** Re-reads the sale, its stock problems and totals; keeps or moves the selection. */
    private void refresh(String selectProductId) {
        Row selected = table.getSelectionModel().getSelectedItem();
        String keep = selectProductId != null ? selectProductId
                : selected == null ? null : selected.line().productId();

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
            rows.add(new Row(i + 1, items.get(i), problems.get(items.get(i).productId())));
        }
        if (keep != null) {
            for (Row r : rows) {
                if (r.line().productId().equals(keep)) {
                    table.getSelectionModel().select(r);
                    table.scrollTo(r);
                }
            }
        }

        SaleTotals t = sale.totals();
        subtotalValue.setText(peso(t.subtotal()));
        vatValue.setText(peso(t.vatAmount()));
        totalValue.setText(peso(t.total()));
        BigDecimal pieces = BigDecimal.ZERO;
        for (SaleLineItem line : items) {
            pieces = pieces.add(line.quantity());
        }
        itemCount.setText(Quantity.format(pieces));
        payButton.setDisable(!canPay);
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
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Log out? This sale has " + sale.getItems().size() + " line(s); they will be discarded. "
                            + "Nothing is saved.", ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Log out");
            confirm.setHeaderText(null);
            confirm.initOwner(screen.stage());
            confirm.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
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

    /** F2 - TODO(skeleton): sell something that is not in the catalog (ask for a name, a price and a quantity). */
    private void openItem() {
        showNotice("Open Item is not available yet.");
    }

    /** F4 - TODO(skeleton): change the unit price of the selected line (decide which role may do it). */
    private void editPrice() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            showError("Select a line first (Up / Down).");
            return;
        }
        showNotice("Edit Price is not available yet.");
    }

    /** F7 - TODO(skeleton): bring back a transaction that was put on hold. */
    private void retrieveTransaction() {
        showNotice("Retrieve Transaction is not available yet.");
    }

    /** F8 - TODO(skeleton): start a new transaction (for example, put this one on hold and begin another). */
    private void newTransaction() {
        showNotice("New Transaction is not available yet.");
    }

    /** Hamburger menu (top right) - TODO(skeleton): add more entries (change password, help, ...) when they exist. */
    private void showMenu(Node anchor) {
        ContextMenu menu = new ContextMenu();
        MenuItem home = new MenuItem("Home  (Esc)");
        home.setOnAction(e -> screen.goHome());
        menu.getItems().add(home);
        menu.show(anchor, Side.BOTTOM, 0, 0);
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

    /** Online - Cashier: name - [message] - menu - Logout. */
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
        Button logout = new Button("Logout");
        PosStyle.plainButton(logout, "-fx-font-size: 22px; -fx-text-fill: " + DARK + "; -fx-padding: 6 12 6 12;");
        logout.setFocusTraversable(false);
        logout.setOnAction(e -> logout());

        HBox bar = new HBox(12, dot, online, spacer(24), cashier, message, menu, logout);
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
        Button cancel = keyButton("Cancel Transaction", "F9", PosStyle.Kind.DANGER, "white", 26, this::cancelSale);
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
        table.getColumns().add(column("Item", 503, r -> r.line().productName()));
        table.getColumns().add(column("Qty", 99, r -> Quantity.format(r.line().quantity()) + " " + r.line().unit()));
        table.getColumns().add(column("Unit Price", 188, r -> Money.format(r.line().unitPrice())));
        table.getColumns().add(column("Price", 218,
                r -> r.line().priceType() == PriceType.WHOLESALE ? "WHOLESALE" : "RETAIL"));
        table.getColumns().add(column("Amount", 270, r -> Money.format(r.line().lineTotal())));
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
        scan.setMinWidth(75);
        scan.setPrefWidth(75);

        Button search = new Button();
        HBox searchRow = new HBox(6, label("Search Product", 15, true), searchIcon());
        searchRow.setAlignment(Pos.CENTER);
        search.setGraphic(keyed(searchRow, "F1", DARK));
        decorate(search, PosStyle.Kind.SECONDARY, this::addFromSearch);
        fixSize(search, 166, 70);

        Button openItem = keyButton("Open Item", "F2", PosStyle.Kind.SECONDARY, DARK, 26, this::openItem);
        Button editQty = keyButton("Edit Qty", "F3", PosStyle.Kind.SECONDARY, DARK, 26, this::askQuantity);
        Button editPrice = keyButton("Edit Price", "F4", PosStyle.Kind.SECONDARY, DARK, 26, this::editPrice);
        Button remove = keyButton("Remove Item", "Del", PosStyle.Kind.DANGER, "white", 22, this::removeSelected);
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
        Label title = label("ORDER SUMMARY", 40, true);
        title.setPadding(new Insets(24, 0, 19, 0));

        VBox items = new VBox(sumRow("Total Items", itemCount, false));
        items.setAlignment(Pos.CENTER_LEFT);
        fixHeight(items, 111);

        VBox amounts = new VBox(10, sumRow("Subtotal", subtotalValue, false), sumRow("VAT", vatValue, false));
        amounts.setPadding(new Insets(42, 0, 0, 0));
        fixHeight(amounts, 153);

        VBox total = new VBox(sumRow("TOTAL", totalValue, true));
        total.setPadding(new Insets(41, 0, 0, 0));

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
        b.setGraphic(keyed(main, key, color));
        decorate(b, kind, onClick);
        return b;
    }

    private static Button iconKeyButton(Node icon, String key, Runnable onClick) {
        Button b = new Button();
        b.setGraphic(keyed(icon, key, DARK));
        decorate(b, PosStyle.Kind.SECONDARY, onClick);
        return b;
    }

    private static Node keyed(Node main, String key, String color) {
        Label hint = new Label("[" + key + "]");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + ";");
        VBox box = new VBox(2, main, hint);
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
        double[] widths = {1, 3, 1, 2, 3, 1, 2, 1, 3, 1, 2, 3, 1};
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
