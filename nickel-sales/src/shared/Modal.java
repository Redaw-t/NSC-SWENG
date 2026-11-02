package shared;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * A pop-up drawn like the design: a white rounded card with a shadow, centred over the app
 * window, which is dimmed and blurred while the pop-up is open. Esc presses the Cancel button
 * and Enter the default one (set with {@code setCancelButton} / {@code setDefaultButton}).
 * Closing the app window's session (idle logout) closes it too.
 *
 * <p>Fill {@link #card()} with a title, the content and {@link #buttons}, then call
 * {@link #showAndWait()}. {@link #confirm} and {@link #notice} are ready-made "Are you sure?"
 * and "OK" pop-ups.
 *
 * <p>This is the shared copy for screens outside the Product module (the Product screens still
 * use their own identical package-private copy, which they can switch to this one later).
 */
public final class Modal {

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final VBox card = new VBox(18);
    private final Window owner;
    private Effect previousEffect;

    /** @param width the card's width in pixels */
    public Modal(Window owner, double width) {
        this.owner = owner;
        card.setMinWidth(width);
        card.setPrefWidth(width);
        card.setMaxWidth(width);
        card.setPadding(new Insets(36, 48, 32, 48));
        Styles.modalCard(card);

        StackPane root = new StackPane(card);
        root.setPadding(new Insets(32));          // room for the shadow
        root.setStyle("-fx-background-color: transparent;");
        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);

        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        } else {
            stage.initModality(Modality.APPLICATION_MODAL);
        }
        stage.setOnShown(e -> center());
        stage.setOnHidden(e -> undim());
    }

    public VBox card() {
        return card;
    }

    public void showAndWait() {
        dim();
        stage.showAndWait();
    }

    public void close() {
        stage.close();
    }

    /* ------------------------------ building blocks ------------------------------ */

    /** The big title at the top. */
    public static Label title(String text) {
        Label label = new Label(text);
        Styles.modalTitle(label);
        label.setWrapText(true);
        return label;
    }

    /** A small grey line under the title. */
    public static Label subtitle(String text) {
        Label label = new Label(text);
        Styles.modalSubtitle(label);
        label.setWrapText(true);
        return label;
    }

    /** A two-column form: labels on the left, fields on the right. */
    public static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(14);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setPercentWidth(36);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setPercentWidth(64);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    /** Adds "label | field" as the next row; returns the following row number. */
    public static int row(GridPane grid, int row, String label, Node field) {
        Label text = new Label(label);
        Styles.modalLabel(text);
        GridPane.setValignment(text, VPos.CENTER);
        GridPane.setValignment(field, VPos.CENTER);
        if (field instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        grid.add(text, 0, row);
        grid.add(field, 1, row);
        return row + 1;
    }

    /** The row of big buttons, centred. */
    public static HBox buttons(Button... buttons) {
        HBox box = new HBox(36, buttons);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(12, 0, 0, 0));
        return box;
    }

    /* ----------------------------- ready-made pop-ups ----------------------------- */

    /** The "Are you sure?" pop-up. True if the user pressed the (red) confirm button. */
    public static boolean confirm(Window owner, String title, String message, String confirmText) {
        Modal modal = new Modal(owner, 520);
        boolean[] confirmed = {false};

        Label body = new Label(message);
        Styles.modalLabel(body);
        body.setWrapText(true);

        Button cancel = new Button("Cancel");
        Styles.modalButton(cancel, Theme.BORDER, Theme.PRIMARY_TEXT);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> modal.close());
        Button confirm = new Button(confirmText);
        Styles.modalButton(confirm, Theme.ACCENT_RED, "white");
        confirm.setOnAction(e -> {
            confirmed[0] = true;
            modal.close();
        });

        modal.card().getChildren().addAll(title(title), body, buttons(cancel, confirm));
        modal.showAndWait();
        return confirmed[0];
    }

    /** A message with a single OK button. */
    public static void notice(Window owner, String title, String message) {
        Modal modal = new Modal(owner, 520);
        Label body = new Label(message);
        Styles.modalLabel(body);
        body.setWrapText(true);

        Button ok = new Button("OK");
        Styles.modalButton(ok, Theme.INFORMATION, "white");
        ok.setDefaultButton(true);
        ok.setCancelButton(true);
        ok.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(title(title), body, buttons(ok));
        modal.showAndWait();
    }

    /* -------------------------------- window logic -------------------------------- */

    private void center() {
        if (owner != null) {
            stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
        } else {
            stage.centerOnScreen();
        }
    }

    private void dim() {
        if (owner == null || owner.getScene() == null) {
            return;
        }
        Node root = owner.getScene().getRoot();
        previousEffect = root.getEffect();
        ColorAdjust darker = new ColorAdjust(0, 0, -0.3, 0);
        darker.setInput(new GaussianBlur(3));
        root.setEffect(darker);
    }

    private void undim() {
        if (owner != null && owner.getScene() != null) {
            owner.getScene().getRoot().setEffect(previousEffect);
        }
    }
}
