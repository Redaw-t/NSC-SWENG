package barcode.ui;

import barcode.service.BarcodeStatus;
import barcode.service.LabelBitmap;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import shared.Styles;
import shared.Theme;

/** Small builders shared by the Barcode screen and its pop-ups, so they look alike. */
final class BarcodeUi {

    private BarcodeUi() { }

    /** The green Assigned / grey Unassigned / red Duplicate-Error pill. */
    static Label pill(BarcodeStatus status, String problem) {
        Label pill = new Label(status.toString());
        switch (status) {
            case ASSIGNED -> Styles.pill(pill, Theme.SUCCESS, "white");
            case UNASSIGNED -> Styles.pill(pill, Theme.BORDER, Theme.SECONDARY_TEXT);
            case ERROR -> Styles.pill(pill, Theme.ERROR, "white");
        }
        if (problem != null) {
            pill.setTooltip(new javafx.scene.control.Tooltip(problem));
        }
        return pill;
    }

    /** A small rounded button for a table row. {@code blue} for the main action, grey for the others. */
    static Button rowButton(String text, boolean blue) {
        Button button = new Button(text);
        if (blue) {
            Styles.rowButton(button, Theme.INFORMATION, "white");
        } else {
            Styles.rowButton(button, Theme.BORDER, Theme.PRIMARY_TEXT);
        }
        return button;
    }

    /** The big grey button of a pop-up (Cancel, Scan, Generate, Close). */
    static Button greyButton(String text) {
        Button button = new Button(text);
        Styles.modalButton(button, Theme.BORDER, Theme.PRIMARY_TEXT);
        return button;
    }

    /** The big blue button of a pop-up (Save Barcode, Print Label). */
    static Button blueButton(String text) {
        Button button = new Button(text);
        Styles.modalButton(button, Theme.INFORMATION, "white");
        return button;
    }

    /** The bold label above a field of a pop-up. */
    static Label fieldLabel(String text) {
        Label label = new Label(text);
        Styles.modalLabel(label);
        return label;
    }

    /** A grey box showing a value the user cannot change (the product name, the barcode). */
    static Label valueBox(String text) {
        Label box = new Label(text);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-background-color: " + Theme.BORDER + "; -fx-background-radius: 4; -fx-padding: 0 14 0 14; "
                + "-fx-pref-height: 44; -fx-min-height: 44; -fx-font-size: 16px; -fx-font-weight: bold; "
                + "-fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return box;
    }

    /** A grey input box as in the design (same as the search box). */
    static TextField input(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        Styles.input(field);
        field.setStyle(field.getStyle() + " -fx-pref-height: 46; -fx-font-size: 17px;");
        return field;
    }

    /** A label and a field, one above the other. */
    static VBox labelled(String label, Node field) {
        VBox box = new VBox(8, fieldLabel(label), field);
        return box;
    }

    /** The barcode drawn as a label would print it, on a white card with the number underneath. */
    static Node preview(String barcode) {
        VBox card = new VBox(4);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(10, 14, 8, 14));
        card.setStyle("-fx-background-color: white; -fx-border-color: " + Theme.BORDER + "; -fx-border-radius: 4;");
        try {
            Label number = new Label(barcode);
            number.setStyle("-fx-font-size: 12px; -fx-text-fill: black;");
            card.getChildren().addAll(BarcodeView.label(barcode, 220, 56), number);
        } catch (RuntimeException e) {
            Label none = new Label("No preview");
            Styles.muted(none);
            card.getChildren().add(none);
        }
        StackPane holder = new StackPane(card);
        holder.setAlignment(Pos.CENTER_LEFT);
        return holder;
    }

    /**
     * The label exactly as it prints: the same bitmap the printer gets, shown dot for dot (1 screen
     * pixel per printer dot) on a white strip like the paper roll.
     */
    static Node labelPreview(LabelBitmap label) {
        if (label == null) {
            Label none = new Label("No preview");
            Styles.muted(none);
            return none;
        }
        WritableImage image = new WritableImage(label.width(), label.height());
        PixelWriter px = image.getPixelWriter();
        for (int y = 0; y < label.height(); y++) {
            for (int x = 0; x < label.width(); x++) {
                px.setColor(x, y, label.isBlack(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        ImageView view = new ImageView(image);
        view.setSmooth(false);   // keep the dots sharp, as on the paper
        StackPane paper = new StackPane(view);
        paper.setPadding(new Insets(14, 0, 14, 0));
        paper.setStyle("-fx-background-color: white; -fx-border-color: " + Theme.BORDER + "; "
                + "-fx-border-style: dashed solid dashed solid;");   // dashed top / bottom: where it tears off
        paper.setEffect(new DropShadow(10, 0, 2, Color.rgb(0, 0, 0, 0.12)));
        paper.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane holder = new StackPane(paper);
        holder.setAlignment(Pos.CENTER);
        return holder;
    }
}
