package pos.ui;

import app.AppModule;
import app.HomeScreen;
import app.ScreenContext;
import auth.permission.Permission;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import pos.repository.PosTables;
import shared.UiErrors;

import java.util.List;

/**
 * "Point of Sale" on the home screen (cashiers and admins): opens the
 * cashier screen ({@link PosScreen}). If Product &amp; Inventory isn't
 * connected on this terminal, or the database's sales tables are out of date,
 * it says so instead.
 */
public final class PosModule implements AppModule {

    @Override
    public String title() {
        return "Point of Sale";
    }

    @Override
    public String description() {
        return "Ring up sales";
    }

    @Override
    public Permission permission() {
        return Permission.PROCESS_SALE;
    }

    @Override
    public Parent open(ScreenContext screen) {
        var app = screen.app();
        if (app.salesService().isEmpty() || app.products().isEmpty()) {
            return message(screen, "Product & Inventory is not connected yet, so sales can't be rung up on this terminal.");
        }
        // Find out now, not at Pay, if the sales tables are from an older SQL script.
        List<String> missing;
        try {
            missing = PosTables.missing();
        } catch (RuntimeException e) {
            return message(screen, UiErrors.message(e, "The sales tables could not be checked. Try again."));
        }
        if (!missing.isEmpty()) {
            System.err.println("[DATABASE] POS tables are out of date; missing: " + String.join(", ", missing));
            return message(screen, UiErrors.DATABASE_OUT_OF_DATE);
        }
        PosScreen pos = new PosScreen(screen, app.salesService().get(), app.payments(), app.products().get());
        Platform.runLater(pos::focusBarcode);
        return pos.getView();
    }

    private static Parent message(ScreenContext screen, String text) {
        Label heading = new Label("Point of Sale - terminal " + screen.app().config().terminalId());
        heading.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: " + HomeScreen.BLUE + ";");
        Label status = new Label(text);
        status.setStyle("-fx-font-size: 16px;");
        status.setWrapText(true);
        status.setMaxWidth(760);
        Button back = new Button("Back to home");
        back.setDefaultButton(true);
        back.setOnAction(e -> screen.goHome());
        VBox view = new VBox(16, heading, status, back);
        view.setAlignment(Pos.CENTER);
        view.setStyle("-fx-background-color: white;");
        return view;
    }
}
