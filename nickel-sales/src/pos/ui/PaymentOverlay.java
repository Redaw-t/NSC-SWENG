package pos.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import pos.cart.Sale;
import pos.checkout.CheckoutException;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import shared.Money;
import shared.Theme;

import java.math.BigDecimal;
import java.util.function.Consumer;

/**
 * F5 on the POS screen: the PAYMENT METHOD card, shown over the dimmed screen.
 *
 * | Key    | Does                                                        |
 * | F1     | Cash: type the amount tendered, the change is shown          |
 * | F2     | Online payment: type the reference number                    |
 * | F3-F5  | (online) GCash / Maya / QRPh                                 |
 * | F8     | (cash) fill in the exact amount                              |
 * | Enter  | Complete Transaction                                         |
 * | Esc    | Cancel (back to the cart)                                    |
 *
 * The amounts are checked by PaymentService; its message is shown in the card and the
 * card stays open until the payment is valid. Nothing is saved here: the POS screen saves
 * the sale with the payment passed to {@code onPaid}.
 *
 * The card is part of the scaled screen (it is added to the POS screen's content), so it
 * is drawn in the same 1920 px design pixels as the mockup.
 *
 * REFUND mode ({@link #showRefund}, Void Item / Void Transaction): the same card titled
 * REFUND METHOD, for choosing how the voided amount goes back, which may differ from how the
 * sale was paid. Cash shows the amount to hand back (nothing to type); online needs the
 * reference number of the transfer to the customer. Enter = Complete Refund.
 */
final class PaymentOverlay {

    /** Turns the card's choice into a payment (or refund); its message is shown if it fails. */
    interface Recorder {
        PaymentRecord record(PaymentMethod method, BigDecimal tendered, String referenceNo) throws CheckoutException;
    }

    private static final double CARD_WIDTH = 986;
    private static final double CARD_HEIGHT = 708;
    private static final double FIELD_WIDTH = 616;
    private static final String DARK = Theme.PRIMARY_TEXT;

    private final StackPane host;
    private final PaymentService payments;
    private final Recorder recorder;
    private final boolean refund;
    private final String subtitle;   // refund mode: e.g. "Void Item · RET for T01-2026-000123"; may be null
    private final BigDecimal total;
    private final Consumer<PaymentRecord> onPaid;
    private final Runnable onClosed;

    private final StackPane layer = new StackPane();
    private PaymentMethod method = PaymentMethod.CASH;
    private PaymentMethod onlineMethod = PaymentMethod.GCASH;

    private final TextField cashField = new TextField();
    private final TextField referenceField = new TextField();
    private final Label changeName = new Label("Change");
    private final Label changeValue = new Label();
    private final Label error = new Label();
    private final VBox cashBox = new VBox();
    private final VBox onlineBox = new VBox();
    private Button cashButton;
    private Button onlineButton;
    private Button gcashButton;
    private Button mayaButton;
    private Button qrphButton;

    private PaymentOverlay(StackPane host, PaymentService payments, BigDecimal total, boolean refund,
                           String subtitle, Recorder recorder, Consumer<PaymentRecord> onPaid, Runnable onClosed) {
        this.host = host;
        this.payments = payments;
        this.total = total;
        this.refund = refund;
        this.subtitle = subtitle;
        this.recorder = recorder;
        this.onPaid = onPaid;
        this.onClosed = onClosed;
    }

    /**
     * Opens the card over {@code host} (the POS screen's content).
     *
     * @param onPaid   called with the payment after the card has closed
     * @param onClosed called when the card has closed, paid or cancelled
     */
    static PaymentOverlay show(StackPane host, PaymentService payments, Sale sale,
                               Consumer<PaymentRecord> onPaid, Runnable onClosed) {
        return open(new PaymentOverlay(host, payments, sale.total(), false, null,
                (m, tendered, ref) -> payments.recordPayment(sale, m, tendered, ref), onPaid, onClosed));
    }

    /**
     * Opens the card in REFUND mode for {@code amount} (see the class comment).
     *
     * @param subtitle   shown under the title, e.g. "Void Item · Invoice T01-2026-000123"
     * @param onRefunded called with the chosen refund after the card has closed
     * @param onClosed   called when the card has closed, completed or cancelled
     */
    static PaymentOverlay showRefund(StackPane host, PaymentService payments, BigDecimal amount, String subtitle,
                                     Consumer<PaymentRecord> onRefunded, Runnable onClosed) {
        return open(new PaymentOverlay(host, payments, amount, true, subtitle,
                (m, tendered, ref) -> payments.recordRefund(amount, m, ref), onRefunded, onClosed));
    }

    private static PaymentOverlay open(PaymentOverlay overlay) {
        overlay.build();
        overlay.host.getChildren().add(overlay.layer);
        overlay.select(PaymentMethod.CASH);
        return overlay;
    }

    /* ------------------------------- layout ------------------------------- */

    private void build() {
        Label titleText = text(refund ? "REFUND METHOD" : "PAYMENT METHOD", 40, true);
        javafx.scene.Node title = titleText;
        if (refund && subtitle != null) {   // e.g. "Void Item · Invoice T01-2026-000123" on the right
            Label sub = new Label(subtitle);
            sub.setStyle("-fx-font-size: 18px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            HBox titleRow = new HBox(titleText, grow(), sub);
            titleRow.setAlignment(Pos.BASELINE_LEFT);
            title = titleRow;
        }
        Label dueName = text(refund ? "Total Refund" : "Total Due", 28, false);
        Label dueValue = text(PosScreen.peso(total), 28, true);
        if (refund) {
            dueValue.setStyle(font(28, true) + " -fx-text-fill: " + PosScreen.RED + ";");
        }
        HBox dueRow = new HBox(dueName, grow(), dueValue);
        dueRow.setAlignment(Pos.CENTER_LEFT);

        cashButton = option("Cash", "F1", 139, () -> select(PaymentMethod.CASH));
        onlineButton = option("Online Payment", "F2", 312, () -> select(onlineMethod));
        HBox methodRow = new HBox(20, cashButton, onlineButton);

        buildCashBox();
        buildOnlineBox();
        StackPane modeBox = new StackPane(cashBox, onlineBox);
        modeBox.setAlignment(Pos.TOP_LEFT);
        modeBox.setMinHeight(224);
        modeBox.setPrefHeight(224);
        modeBox.setMaxHeight(224);

        error.setWrapText(true);
        error.setAlignment(Pos.CENTER);
        error.setMaxWidth(CARD_WIDTH - 94);
        error.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.RED + ";");
        VBox errorBox = new VBox(error);
        errorBox.setAlignment(Pos.CENTER);
        errorBox.setMinHeight(65);
        errorBox.setPrefHeight(65);
        errorBox.setMaxHeight(65);

        Button cancel = action("Cancel", "ESC", PosStyle.Kind.SECONDARY, DARK, 150, this::cancel);
        Button complete = refund
                ? action("Complete Refund", "ENTER", PosStyle.Kind.DANGER, "white", 308, this::complete)
                : action("Complete Transaction", "ENTER", PosStyle.Kind.PRIMARY, "white", 308, this::complete);
        HBox buttons = new HBox(40, cancel, complete);
        buttons.setAlignment(Pos.CENTER);

        VBox card = new VBox(title, gap(19), dueRow, gap(16), divider(), gap(45), methodRow, gap(42),
                modeBox, errorBox, buttons);
        card.setPadding(new Insets(51, 47, 44, 47));
        card.setMinSize(CARD_WIDTH, CARD_HEIGHT);
        card.setPrefSize(CARD_WIDTH, CARD_HEIGHT);
        card.setMaxSize(CARD_WIDTH, CARD_HEIGHT);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 34;");
        card.setEffect(new DropShadow(90, 0, 30, Color.rgb(0, 0, 0, 0.35)));

        layer.setStyle("-fx-background-color: rgba(0, 0, 0, 0.10);");
        layer.setAlignment(Pos.CENTER);
        layer.getChildren().add(card);
        // Clicks on the dimmed screen do nothing, except to bring the cursor back to the field.
        layer.setOnMouseClicked(e -> {
            currentField().requestFocus();
            e.consume();
        });
    }

    private void buildCashBox() {
        if (refund) {
            // nothing to type: the cashier hands back exactly the refund
            Label label = text("Cash to Hand Back", 28, false);
            cashField.setText(Money.format(total));
            cashField.setEditable(false);
            styleInput(cashField);
            HBox field = inputBox(text(PosScreen.PESO, 26, true), cashField);
            Label note = new Label("Give the customer this amount from the cash drawer.");
            note.setStyle("-fx-font-size: 20px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            cashBox.getChildren().addAll(label, gap(18), field, gap(30), note);
            return;
        }
        Label label = text("Amount Tendered", 28, false);
        Label peso = text(PosScreen.PESO, 26, true);
        cashField.setPromptText("0.00");
        styleInput(cashField);
        cashField.textProperty().addListener((o, was, is) -> updateChange());
        HBox field = inputBox(peso, cashField);

        changeName.setStyle(font(28, false));
        changeValue.setStyle(font(28, true));
        HBox changeRow = new HBox(changeName, grow(), changeValue);
        changeRow.setAlignment(Pos.CENTER_LEFT);
        changeRow.setPrefWidth(FIELD_WIDTH + 280);

        cashBox.getChildren().addAll(label, gap(18), field, gap(42), changeRow, gap(16), divider());
    }

    private void buildOnlineBox() {
        Label label = text("Reference Number", 28, false);
        referenceField.setPromptText(refund ? "Reference number of the refund sent to the customer"
                : "Reference number from the customer's confirmation");
        styleInput(referenceField);
        HBox field = inputBox(null, referenceField);

        gcashButton = option("GCash", "F3", 139, () -> select(PaymentMethod.GCASH));
        mayaButton = option("Maya", "F4", 139, () -> select(PaymentMethod.MAYA));
        qrphButton = option("QRPh", "F5", 139, () -> select(PaymentMethod.QRPH));
        HBox sub = new HBox(20, gcashButton, mayaButton, qrphButton);

        onlineBox.getChildren().addAll(label, gap(18), field, gap(59), sub);
    }

    /** The grey rounded input: an optional prefix (the peso sign) and a plain text field. */
    private HBox inputBox(Label prefix, TextField field) {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(0, 20, 0, 14));
        box.setMinSize(FIELD_WIDTH, 55);
        box.setPrefSize(FIELD_WIDTH, 55);
        box.setMaxSize(FIELD_WIDTH, 55);
        box.setStyle("-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 10;");
        if (prefix != null) {
            box.getChildren().add(prefix);
        }
        HBox.setHgrow(field, Priority.ALWAYS);
        box.getChildren().add(field);
        return box;
    }

    private static void styleInput(TextField field) {
        field.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-font-size: 26px; "
                + "-fx-font-weight: bold; -fx-text-fill: " + DARK + "; -fx-prompt-text-fill: " + Theme.SECONDARY_TEXT + "; "
                + "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
    }

    /* ------------------------------- behaviour ------------------------------- */

    private void select(PaymentMethod m) {
        method = m;
        if (m != PaymentMethod.CASH) {
            onlineMethod = m;
        }
        boolean cash = m == PaymentMethod.CASH;
        cashBox.setVisible(cash);
        cashBox.setManaged(cash);
        onlineBox.setVisible(!cash);
        onlineBox.setManaged(!cash);
        mark(cashButton, cash);
        mark(onlineButton, !cash);
        mark(gcashButton, m == PaymentMethod.GCASH);
        mark(mayaButton, m == PaymentMethod.MAYA);
        mark(qrphButton, m == PaymentMethod.QRPH);
        error.setText("");
        updateChange();
        currentField().requestFocus();
        Platform.runLater(() -> currentField().requestFocus());   // again once the card is on screen
    }

    private TextField currentField() {
        return method == PaymentMethod.CASH ? cashField : referenceField;
    }

    private void updateChange() {
        if (refund) {
            error.setText("");
            return;   // no change in a refund
        }
        BigDecimal tendered = parseMoney(cashField.getText());
        if (tendered == null) {
            changeName.setText("Change");
            changeValue.setText(PosScreen.peso(BigDecimal.ZERO));
            changeValue.setStyle(font(28, true) + " -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        } else if (tendered.compareTo(total) < 0) {
            changeName.setText("Short by");
            changeValue.setText(PosScreen.peso(total.subtract(tendered)));
            changeValue.setStyle(font(28, true) + " -fx-text-fill: " + PosScreen.RED + ";");
        } else {
            changeName.setText("Change");
            changeValue.setText(PosScreen.peso(payments.computeChange(total, tendered)));
            changeValue.setStyle(font(28, true) + " -fx-text-fill: " + DARK + ";");
        }
        error.setText("");
    }

    /**
     * The card's keys. The POS screen passes every key press here while the card is open, so the
     * card answers even if the cursor is not in one of its fields at that moment.
     */
    void handleKey(KeyEvent e) {
        KeyCode k = e.getCode();
        if (k == KeyCode.F1) {
            select(PaymentMethod.CASH);
        } else if (k == KeyCode.F2) {
            select(onlineMethod);
        } else if (k == KeyCode.F3) {
            select(PaymentMethod.GCASH);
        } else if (k == KeyCode.F4) {
            select(PaymentMethod.MAYA);
        } else if (k == KeyCode.F5) {
            select(PaymentMethod.QRPH);
        } else if (k == KeyCode.F8) {
            if (method == PaymentMethod.CASH && !refund) {
                cashField.setText(total.toPlainString());
                cashField.positionCaret(cashField.getText().length());
            }
        } else if (k == KeyCode.ENTER) {
            complete();
        } else if (k == KeyCode.ESCAPE) {
            cancel();
        } else if (k == KeyCode.TAB) {
            // nothing behind the card may take the focus
        } else {
            return;
        }
        e.consume();
    }

    private void complete() {
        try {
            BigDecimal tendered = null;
            if (method == PaymentMethod.CASH && !refund) {
                String text = cashField.getText() == null ? "" : cashField.getText().trim();
                tendered = text.isEmpty() ? null : parseMoney(text);
                if (!text.isEmpty() && tendered == null) {
                    throw new CheckoutException("Type the amount tendered as a number, e.g. 500 or 1000.50.");
                }
            }
            PaymentRecord payment = recorder.record(method, tendered, referenceField.getText());
            close();
            onPaid.accept(payment);
        } catch (CheckoutException ex) {
            error.setText(ex.getMessage());
            (method == PaymentMethod.CASH ? cashField : referenceField).requestFocus();
        }
    }

    private void cancel() {
        close();
    }

    private void close() {
        host.getChildren().remove(layer);
        onClosed.run();
    }

    /** "1,000.50" or "1000.5" as money; null if it isn't a non-negative number. */
    static BigDecimal parseMoney(String text) {
        if (text == null) {
            return null;
        }
        try {
            BigDecimal v = Money.of(text.replace(",", "").replace(PosScreen.PESO, "").trim());
            return Money.isNegative(v) ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* ------------------------------- widgets ------------------------------- */

    private static String font(int size, boolean bold) {
        return "-fx-font-size: " + size + "px; -fx-text-fill: " + DARK + ";" + (bold ? " -fx-font-weight: bold;" : "");
    }

    private static Label text(String value, int size, boolean bold) {
        Label label = new Label(value);
        label.setStyle(font(size, bold));
        return label;
    }

    private static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Region gap(double height) {
        Region r = new Region();
        r.setMinHeight(height);
        r.setPrefHeight(height);
        r.setMaxHeight(height);
        return r;
    }

    private static Region divider() {
        Region r = new Region();
        r.setMinHeight(1);
        r.setPrefHeight(1);
        r.setMaxHeight(1);
        r.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return r;
    }

    /** A method choice (Cash, Online Payment, GCash ...): blue when chosen, grey otherwise. */
    private static Button option(String name, String key, double width, Runnable onClick) {
        Button b = action(name, key, PosStyle.Kind.SECONDARY, DARK, width, onClick);
        b.setUserData(name);
        return b;
    }

    private static void mark(Button b, boolean selected) {
        PosStyle.button(b, selected ? PosStyle.Kind.PRIMARY : PosStyle.Kind.SECONDARY);
        String color = selected ? "white" : DARK;
        for (javafx.scene.Node n : ((VBox) b.getGraphic()).getChildren()) {
            ((Label) n).setStyle(((Label) n).getStyle().replaceAll("-fx-text-fill: [^;]+;", "-fx-text-fill: " + color + ";"));
        }
    }

    /** A 55 px button with the name and its key underneath, like the mockup. */
    private static Button action(String name, String key, PosStyle.Kind kind, String color, double width, Runnable onClick) {
        Label main = new Label(name);
        main.setStyle("-fx-font-size: 28px; -fx-text-fill: " + color + ";");
        Label hint = new Label("[" + key + "]");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + color + ";");
        VBox graphic = new VBox(-4, main, hint);
        graphic.setAlignment(Pos.CENTER);
        Button b = new Button();
        b.setGraphic(graphic);
        PosStyle.button(b, kind);
        b.setFocusTraversable(false);
        b.setMinSize(width, 55);
        b.setPrefSize(width, 55);
        b.setMaxSize(width, 55);
        b.setOnAction(e -> onClick.run());
        return b;
    }
}
