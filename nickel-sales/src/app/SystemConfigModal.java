package app;

import app.install.PosTerminalRepository;
import auth.permission.Permission;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import system.service.StoreDetailsService;
import system.ui.StoreDetailsModals;

import java.util.Optional;

/**
 * Settings &gt; General &gt; System Configuration (Admin only): a pop-up with
 * - Store Details: what receipts print (opens its own pop-up, with Edit and View Logs);
 * - Change Main POS IP Address: on an Additional POS, the Main POS it syncs with (not on the Main POS).
 */
final class SystemConfigModal {

    private SystemConfigModal() { }

    /** True if this user may open it (Admin). */
    static boolean allowed(ScreenContext screen) {
        try {
            return screen.app().authz().checkAccess(screen.user(), Permission.MANAGE_SYSTEM);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void show(ScreenContext screen) {
        Modal modal = new Modal(screen.stage(), 640);

        Optional<StoreDetailsService> store = screen.app().find(StoreDetailsService.class);
        Button storeDetails = option("Store Details");
        storeDetails.setDisable(store.isEmpty());
        storeDetails.setOnAction(e -> store.ifPresent(s -> StoreDetailsModals.show(screen, s, modal.window())));
        VBox storeRow = choice(storeDetails, "The store name, address, proprietor, TIN and phone printed at the top of "
                + "every receipt, and the log of every change.");

        // The Main POS's address: only an Additional POS has one to change.
        PosTerminalRepository terminals = new PosTerminalRepository();
        String note;
        String key = null;
        String current = null;
        boolean additional = false;
        try {
            Optional<String> me = terminals.thisTerminalId();
            if (me.isEmpty()) {
                note = "This computer is not set up as a register yet.";
            } else if (PosTerminalRepository.MAIN_ID.equals(me.get())) {
                note = "This computer IS the Main POS: the other registers connect to it, so there is no address "
                        + "to change here.";
            } else {
                key = terminals.apiKey().orElse(null);
                current = terminals.mainPosAddress().orElse("");
                additional = key != null;
                note = "The address of the Main POS this register syncs with"
                        + (current.isBlank() ? "." : " (now " + current + ").");
            }
        } catch (RuntimeException e) {
            note = "This computer's register settings can't be read right now.";
        }
        Button mainIp = option("Change Main POS IP Address");
        mainIp.setDisable(!additional);
        String apiKey = key;
        String address = current;
        mainIp.setOnAction(e -> MainPosAddressModal.show(screen, terminals, apiKey, address, modal.window()));
        VBox ipRow = choice(mainIp, note);

        Region line = new Region();
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setStyle("-fx-background-color: " + Theme.BORDER + ";");

        Button close = new Button("Close");
        Styles.modalButton(close, Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title("System Configuration"),
                Modal.subtitle("Settings the store's registers share."), storeRow, line, ipRow, Modal.buttons(close));
        modal.showAndWait();
    }

    /** A wide button for one choice. */
    private static Button option(String text) {
        Button b = new Button(text);
        Styles.modalButton(b, Theme.PRIMARY_BLUE, "white");
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    /** The choice's button with a line saying what it is under it. */
    private static VBox choice(Button button, String text) {
        Label label = new Label(text);
        Styles.modalSubtitle(label);
        label.setWrapText(true);
        HBox row = new HBox(button);
        HBox.setHgrow(button, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, row, label);
    }
}
