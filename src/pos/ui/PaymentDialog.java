package pos.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import pos.cart.Sale;
import pos.checkout.CheckoutException;
import pos.checkout.PaymentService;
import pos.contract.PaymentMethod;
import pos.contract.PaymentRecord;
import shared.Money;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * F12 on the POS screen: take the payment.
 *
 * - Cash (default): type the cash received; the change is shown as you type.
 *   F8 fills in the exact amount.
 * - GCash / Maya / QRPh: type the reference number from the customer's
 *   confirmation (the system does not process the payment itself).
 * - Switch method with F5 (next) or Alt+C / Alt+G / Alt+M / Alt+Q.
 * - Enter completes the sale; Esc goes back to the cart.
 *
 * The amounts are checked by PaymentService; its message is shown here and
 * the dialog stays open until the payment is valid. Nothing is saved here:
 * the POS screen saves the sale with the returned payment.
 */
final class PaymentDialog {

    private PaymentDialog() { }

    static Optional<PaymentRecord> ask(Window owner, PaymentService payments, Sale sale) {
        BigDecimal total = sale.total();
        Dialog<PaymentRecord> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Payment");
        dialog.setHeaderText(null);

        Label totalLabel = new Label("TOTAL  " + PosScreen.peso(total));
        totalLabel.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.BLUE + ";");

        ToggleGroup group = new ToggleGroup();
        RadioButton cash = method("_Cash", PaymentMethod.CASH, group);
        RadioButton gcash = method("_GCash", PaymentMethod.GCASH, group);
        RadioButton maya = method("_Maya", PaymentMethod.MAYA, group);
        RadioButton qrph = method("_QRPh", PaymentMethod.QRPH, group);
        RadioButton[] all = {cash, gcash, maya, qrph};
        cash.setSelected(true);
        HBox methods = new HBox(18, cash, gcash, maya, qrph);

        TextField cashField = new TextField();
        cashField.setPromptText("Cash received");
        cashField.setStyle("-fx-font-size: 22px;");
        Button exact = new Button("Exact  (F8)");
        exact.setFocusTraversable(false);
        exact.setOnAction(e -> cashField.setText(total.toPlainString()));
        Label change = new Label();
        change.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        TextField referenceField = new TextField();
        referenceField.setPromptText("Reference number");
        referenceField.setStyle("-fx-font-size: 22px;");

        Label fieldLabel = new Label("Cash received");
        fieldLabel.setStyle("-fx-font-size: 15px;");
        Label error = new Label();
        error.setWrapText(true);
        error.setMaxWidth(460);
        error.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.RED + ";");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        HBox cashRow = new HBox(10, cashField, exact);
        form.add(fieldLabel, 0, 0);
        form.add(cashRow, 0, 1);
        form.add(referenceField, 0, 1);
        form.add(change, 0, 2);

        Runnable update = () -> {
            PaymentMethod m = (PaymentMethod) group.getSelectedToggle().getUserData();
            boolean isCash = m == PaymentMethod.CASH;
            cashRow.setVisible(isCash);
            cashRow.setManaged(isCash);
            referenceField.setVisible(!isCash);
            referenceField.setManaged(!isCash);
            change.setVisible(isCash);
            fieldLabel.setText(isCash ? "Cash received" : PaymentService.label(m) + " reference number");
            error.setText("");
            if (isCash) {
                BigDecimal tendered = parseMoney(cashField.getText());
                if (tendered == null) {
                    change.setText("Change: -");
                    change.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #555555;");
                } else if (tendered.compareTo(total) < 0) {
                    change.setText("Short by " + PosScreen.peso(total.subtract(tendered)));
                    change.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.RED + ";");
                } else {
                    change.setText("Change: " + PosScreen.peso(payments.computeChange(total, tendered)));
                    change.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + PosScreen.GREEN + ";");
                }
            }
            Platform.runLater(() -> (isCash ? cashField : referenceField).requestFocus());
        };
        group.selectedToggleProperty().addListener((o, was, is) -> {
            if (is == null) {
                was.setSelected(true);   // always keep one method selected
            } else {
                update.run();
            }
        });
        cashField.textProperty().addListener((o, was, is) -> update.run());

        ButtonType complete = new ButtonType("Complete sale", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(complete, ButtonType.CANCEL);
        Label keys = new Label("Enter complete  ·  Esc back  ·  F5 next method  ·  F8 exact cash");
        keys.setStyle("-fx-font-size: 12px; -fx-text-fill: #666666;");
        dialog.getDialogPane().setContent(new VBox(14, totalLabel, methods, form, error, keys));
        dialog.getDialogPane().setMinWidth(520);

        PaymentRecord[] result = new PaymentRecord[1];
        dialog.getDialogPane().lookupButton(complete).addEventFilter(ActionEvent.ACTION, e -> {
            PaymentMethod m = (PaymentMethod) group.getSelectedToggle().getUserData();
            try {
                BigDecimal tendered = null;
                if (m == PaymentMethod.CASH) {
                    String text = cashField.getText().trim();
                    tendered = text.isEmpty() ? null : parseMoney(text);
                    if (!text.isEmpty() && tendered == null) {
                        throw new CheckoutException("Type the cash received as a number, e.g. 500 or 1000.50.");
                    }
                }
                result[0] = payments.recordPayment(sale, m, tendered, referenceField.getText());
            } catch (CheckoutException ex) {
                error.setText(ex.getMessage());
                (m == PaymentMethod.CASH ? cashField : referenceField).requestFocus();
                e.consume();   // keep the dialog open
            }
        });
        dialog.setResultConverter(b -> b == complete ? result[0] : null);

        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F5) {
                for (int i = 0; i < all.length; i++) {
                    if (all[i].isSelected()) {
                        all[(i + 1) % all.length].setSelected(true);
                        break;
                    }
                }
                e.consume();
            } else if (e.getCode() == KeyCode.F8 && cash.isSelected()) {
                exact.fire();
                e.consume();
            }
        });

        update.run();
        return dialog.showAndWait();
    }

    private static RadioButton method(String text, PaymentMethod m, ToggleGroup group) {
        RadioButton b = new RadioButton(text);
        b.setMnemonicParsing(true);
        b.setUserData(m);
        b.setToggleGroup(group);
        b.setFocusTraversable(false);
        b.setStyle("-fx-font-size: 16px;");
        return b;
    }

    /** "1,000.50" or "1000.5" as money; null if it isn't a non-negative number. */
    private static BigDecimal parseMoney(String text) {
        try {
            BigDecimal v = Money.of(text.replace(",", "").replace(PosScreen.PESO, "").trim());
            return Money.isNegative(v) ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
