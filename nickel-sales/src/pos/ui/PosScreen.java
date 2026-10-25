package pos.ui;

import app.HomeScreen;
import app.ScreenContext;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
import shared.UiErrors;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The cashier's screen (POSScreen in the class diagram). Everything works
 * from the keyboard; a barcode scanner acts as a keyboard that types the code
 * and presses Enter.
 *
 * | Key          | Does                                                     |
 * | Enter        | (barcode box) add the item; (quantity box) go to barcode  |
 * | F2           | quantity box: type e.g. 12 or 1.5, Enter, then scan       |
 * | F3           | search for a product (items without a barcode)            |
 * | F4 / Enter   | (cart) change the selected line's quantity                |
 * | + / -        | (cart) one more / one less of the selected line           |
 * | Delete       | (cart) remove the selected line                           |
 * | Up / Down    | move in the cart (from the barcode box too)               |
 * | F7           | reprint a receipt by invoice number                       |
 * | F9           | cancel the sale                                           |
 * | F12          | pay (only when the sale can be paid)                      |
 * | Esc          | back to the home screen (the sale stays open)             |
 *
 * All rules live in SalesService and PaymentService; this screen only shows
 * their results and messages.
 */
public final class PosScreen {

    static final String BLUE = HomeScreen.BLUE;
    static final String RED = "#a61c1c";
    static final String GREEN = "#1d6b34";
    static final String PESO = "₱";

    private final ScreenContext screen;
    private final SalesService pos;
    private final PaymentService payments;
    private final ProductLookup products;

    private final BorderPane view = new BorderPane();
    private final TextField quantityField = new TextField("1");
    private final TextField barcodeField = new TextField();
    private final TableView<Row> table = new TableView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Label message = new Label();
    private final Label subtotalValue = new Label();
    private final Label vatableValue = new Label();
    private final Label vatValue = new Label();
    private final Label exemptValue = new Label();
    private final Label totalValue = new Label();
    private final Label itemCount = new Label();
    private final Label payHint = new Label();
    private final Button payButton = new Button("Pay  (F12)");
    private String lastInvoice;   // filled in for F7 reprint

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
        String code = barcodeField.getText().trim();
        if (code.isEmpty()) {
            showError("Scan or type a barcode. F3 searches for products without one.");
            return;
        }
        BigDecimal qty = readQuantity();
        if (qty == null) {
            return;
        }
        run(() -> {
            SaleLineItem line = pos.addItem(code, qty);
            barcodeField.clear();
            quantityField.setText("1");
            refresh(line.productId());
            showInfo("Added " + describe(line));
        }, () -> barcodeField.selectAll());
    }

    private void addFromSearch() {
        BigDecimal qty = readQuantity();
        if (qty == null) {
            return;
        }
        Optional<ProductInfo> picked;
        try {
            picked = ProductSearchDialog.pick(screen.stage(), products, barcodeField.getText().trim());
        } catch (RuntimeException e) {
            showError(UiErrors.message(e, "Products could not be searched. Try again."));
            return;
        }
        if (picked.isEmpty()) {
            focusBarcode();
            return;
        }
        run(() -> {
            SaleLineItem line = pos.addProduct(picked.get().productId(), qty);
            barcodeField.clear();
            quantityField.setText("1");
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
                "Cancel this sale? All " + sale.getItems().size() + " line(s) will be removed. Nothing is saved.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Cancel sale");
        confirm.setHeaderText(null);
        confirm.initOwner(screen.stage());
        confirm.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            pos.cancelSale();
            pos.startSale(screen.user().getUserId());
            refresh(null);
            showInfo("Sale cancelled. Ready for the next customer.");
        }
        focusBarcode();
    }

    private void pay() {
        if (payButton.isDisable()) {
            showError(payHint.getText().isEmpty() ? "This sale can't be paid yet." : payHint.getText());
            return;
        }
        Sale sale = pos.currentSale().orElseThrow();
        Optional<PaymentRecord> payment = PaymentDialog.ask(screen.stage(), payments, sale);
        if (payment.isEmpty()) {
            focusBarcode();
            return;
        }
        SaleRecord done;
        try {
            done = pos.finalizeSale(payment.get());
        } catch (CartException | CheckoutException | InsufficientStockException e) {
            refresh(null);
            showError(e.getMessage() + " Nothing was saved.");
            focusBarcode();
            return;
        } catch (RuntimeException e) {
            refresh(null);
            showError(UiErrors.message(e, "The sale could not be saved. Nothing was saved; try Pay again."));
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
                    + "Press F7 to reprint it.");
        }
        focusBarcode();
    }

    /** F7: find a saved sale by invoice number and show / print its receipt again. */
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
        vatableValue.setText(peso(t.vatableSales()));
        vatValue.setText(peso(t.vatAmount()));
        exemptValue.setText(peso(t.vatExemptSales()));
        totalValue.setText(peso(t.total()));
        int lines = items.size();
        itemCount.setText(lines == 1 ? "1 line" : lines + " lines");
        payButton.setDisable(!canPay);
        payHint.setText(hint);
    }

    private void showError(String text) {
        message.setText(text);
        message.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + RED + ";");
    }

    private void showInfo(String text) {
        message.setText(text);
        message.setStyle("-fx-font-size: 16px; -fx-text-fill: " + GREEN + ";");
    }

    /** The quantity box as a number, or null (with a message) if it isn't one. */
    private BigDecimal readQuantity() {
        String text = quantityField.getText().trim();
        if (text.isEmpty()) {
            quantityField.setText("1");
            return Quantity.ONE;
        }
        try {
            return Quantity.of(text);
        } catch (NumberFormatException e) {
            showError("Quantity must be a number, e.g. 2 or 1.5 (F2 to fix it).");
            return null;
        }
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

    /* ------------------------------- layout ------------------------------- */

    private void buildView() {
        // --- top bar ---
        Label title = new Label("POINT OF SALE");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");
        Label who = new Label("Cashier: " + screen.user().getFullName() + "  ·  Terminal "
                + screen.app().config().terminalId());
        who.setStyle("-fx-font-size: 14px; -fx-text-fill: #444444;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button back = new Button("Home  (Esc)");
        back.setFocusTraversable(false);
        back.setOnAction(e -> screen.goHome());
        HBox top = new HBox(16, title, who, spacer, back);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.setStyle("-fx-border-color: transparent transparent #d0d7e5 transparent;");

        // --- entry row ---
        quantityField.setPrefColumnCount(5);
        quantityField.setStyle("-fx-font-size: 20px;");
        quantityField.setAccessibleText("Quantity");
        quantityField.focusedProperty().addListener((o, was, is) -> {
            if (is) {
                Platform.runLater(quantityField::selectAll);
            }
        });
        quantityField.setOnAction(e -> focusBarcode());
        barcodeField.setPromptText("Scan or type a barcode, then Enter");
        barcodeField.setStyle("-fx-font-size: 20px;");
        barcodeField.setAccessibleText("Barcode");
        barcodeField.setOnAction(e -> addFromBarcode());
        HBox.setHgrow(barcodeField, Priority.ALWAYS);
        Button add = new Button("Add");
        add.setFocusTraversable(false);
        add.setStyle("-fx-font-size: 16px;");
        add.setOnAction(e -> addFromBarcode());
        Button search = new Button("Search  (F3)");
        search.setFocusTraversable(false);
        search.setStyle("-fx-font-size: 16px;");
        search.setOnAction(e -> addFromSearch());
        Label qtyLabel = new Label("Qty (F2)");
        Label times = new Label("×");
        times.setStyle("-fx-font-size: 20px;");
        HBox entry = new HBox(10, qtyLabel, quantityField, times, barcodeField, add, search);
        entry.setAlignment(Pos.CENTER_LEFT);

        // --- cart table ---
        table.setItems(rows);
        table.setPlaceholder(new Label("No items yet. Scan a barcode to start."));
        table.getColumns().add(column("#", 36, r -> String.valueOf(r.no()), "CENTER"));
        TableColumn<Row, String> item = column("Item", 230, r -> r.line().productName(), "CENTER_LEFT");
        table.getColumns().add(item);
        table.getColumns().add(column("Qty", 80, r -> Quantity.format(r.line().quantity()) + " " + r.line().unit(), "CENTER_RIGHT"));
        table.getColumns().add(column("Unit price", 95, r -> peso(r.line().unitPrice()), "CENTER_RIGHT"));
        table.getColumns().add(column("Price", 105,
                r -> r.line().priceType() == PriceType.WHOLESALE ? "Wholesale" : "Retail", "CENTER"));
        table.getColumns().add(column("Amount", 105, r -> peso(r.line().lineTotal()), "CENTER_RIGHT"));
        // Short note on the line; the full sentence is shown under the table.
        TableColumn<Row, String> note = column("Note", 140, r -> r.problem() == null ? ""
                : r.problem().noLongerSold() ? "No longer sold"
                : "Only " + Quantity.format(r.problem().available()) + " left", "CENTER_LEFT");
        note.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                setStyle("-fx-text-fill: " + RED + "; -fx-font-weight: bold;");
            }
        });
        table.getColumns().add(note);
        // "Item" takes whatever width the other columns leave, so nothing scrolls sideways.
        item.prefWidthProperty().bind(table.widthProperty().subtract(36 + 80 + 95 + 105 + 105 + 140 + 20));
        table.setRowFactory(t -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                setStyle(!empty && row != null && row.problem() != null ? "-fx-background-color: #fbe3e3;" : "");
            }
        });
        table.setStyle("-fx-font-size: 15px;");
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
        VBox.setVgrow(table, Priority.ALWAYS);

        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        Label keys = new Label("Enter add  ·  F2 qty  ·  F3 search  ·  ↑↓ select line  ·  "
                + "F4 / Enter change qty  ·  + / - one more / less  ·  Del remove  ·  F7 reprint  ·  F9 cancel sale  ·  "
                + "F12 pay  ·  Esc home");
        keys.setStyle("-fx-font-size: 12px; -fx-text-fill: #666666;");
        keys.setWrapText(true);
        VBox left = new VBox(12, entry, table, message, keys);
        left.setPadding(new Insets(16));

        // --- totals panel ---
        GridPane sums = new GridPane();
        sums.setHgap(16);
        sums.setVgap(8);
        addSum(sums, 0, "Subtotal (before VAT)", subtotalValue);
        addSum(sums, 1, "  VATable sales", vatableValue);
        addSum(sums, 2, "  VAT-exempt sales", exemptValue);
        addSum(sums, 3, "VAT (12%)", vatValue);
        Label totalLabel = new Label("TOTAL");
        totalLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        totalValue.setStyle("-fx-font-size: 40px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");
        itemCount.setStyle("-fx-font-size: 13px; -fx-text-fill: #555555;");
        payButton.setMaxWidth(Double.MAX_VALUE);
        payButton.setFocusTraversable(false);
        payButton.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-background-color: " + BLUE
                + "; -fx-text-fill: white; -fx-padding: 14;");
        payButton.setOnAction(e -> pay());
        payHint.setWrapText(true);
        payHint.setStyle("-fx-font-size: 13px; -fx-text-fill: " + RED + ";");
        Button cancel = new Button("Cancel sale  (F9)");
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setFocusTraversable(false);
        cancel.setOnAction(e -> cancelSale());
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox right = new VBox(12, sums, new Region(), totalLabel, totalValue, itemCount, grow, payHint, payButton, cancel);
        right.setPadding(new Insets(16));
        right.setPrefWidth(320);
        right.setMinWidth(300);
        right.setStyle("-fx-background-color: #f4f6fa; -fx-border-color: transparent transparent transparent #d0d7e5;");

        view.setTop(top);
        view.setCenter(left);
        view.setRight(right);
        view.setStyle("-fx-background-color: white;");

        // --- screen-wide keys ---
        view.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode k = e.getCode();
            if (k == KeyCode.F2) {
                quantityField.requestFocus();
            } else if (k == KeyCode.F3) {
                addFromSearch();
            } else if (k == KeyCode.F4) {
                askQuantity();
            } else if (k == KeyCode.F7) {
                reprint();
            } else if (k == KeyCode.F9) {
                cancelSale();
            } else if (k == KeyCode.F12) {
                pay();
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

    private static void addSum(GridPane grid, int row, String name, Label value) {
        Label label = new Label(name);
        label.setStyle("-fx-font-size: 15px;");
        value.setStyle("-fx-font-size: 15px;");
        grid.add(label, 0, row);
        grid.add(value, 1, row);
        GridPane.setHalignment(value, javafx.geometry.HPos.RIGHT);
    }

    private static TableColumn<Row, String> column(String name, double width, Function<Row, String> value,
                                                   String alignment) {
        TableColumn<Row, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setReorderable(false);
        c.setStyle("-fx-alignment: " + alignment + ";");
        return c;
    }
}
