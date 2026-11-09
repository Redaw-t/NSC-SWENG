package app;

import app.install.LocalAddress;
import app.install.PosTerminalRepository;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.Theme;
import sync.api.ApiServer;
import sync.api.MainPosClient;
import sync.discovery.MainPosScanner;

import java.util.Optional;

/**
 * Change Main POS IP Address (Admin only, on an Additional POS, from Settings &gt; General &gt; System
 * Configuration): the Main POS's address this computer syncs with, e.g. after the router gave the Main
 * POS a new one. Find Automatically looks for it on the network; Check and Save saves an address only if
 * it answers as the Main POS (T01) and accepts this store's API key. Otherwise nothing changes.
 */
final class MainPosAddressModal {

    private MainPosAddressModal() { }

    /** The pop-up, over {@code owner}. {@code key}: this store's API key; {@code current}: the saved address. */
    static void show(ScreenContext screen, PosTerminalRepository terminals, String key, String current, Window owner) {
        Modal modal = new Modal(owner, 640);

        Label saved = new Label("Now: " + (current == null || current.isBlank() ? "not set" : current));
        Styles.modalValue(saved);
        TextField address = new TextField(current == null ? "" : current);
        address.setPromptText("e.g. 192.168.1.20");
        Styles.input(address);
        address.setPrefWidth(300);
        Button find = new Button("Find Automatically");
        Styles.smallPillButton(find);
        HBox line = new HBox(12, address, find);
        line.setAlignment(Pos.CENTER_LEFT);

        Label result = new Label();
        result.setWrapText(true);
        result.setManaged(false);
        result.setVisible(false);

        Button close = new Button("Close");
        Styles.modalButton(close, Theme.BORDER, Theme.PRIMARY_TEXT);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());
        Button save = new Button("Check and Save");
        Styles.modalButton(save, Theme.PRIMARY_BLUE, "white");
        save.setDefaultButton(true);

        Runnable busy = () -> {
            find.setDisable(true);
            save.setDisable(true);
            address.setDisable(true);
        };
        Runnable done = () -> {
            find.setDisable(false);
            save.setDisable(false);
            address.setDisable(false);
        };

        find.setOnAction(e -> {
            busy.run();
            show(modal, result, "Looking for the Main POS on the network (a few seconds)...", false);
            background("settings-find-main", () -> {
                Optional<String> found = MainPosScanner.find(ApiServer.port(), key, SyncRuntime.FIND_LIMIT, current);
                Platform.runLater(() -> {
                    done.run();
                    if (found.isPresent()) {
                        address.setText(found.get());
                        show(modal, result, "Found the Main POS at " + found.get() + ". Click Check and Save to use it.",
                                false);
                    } else {
                        show(modal, result, "The Main POS was not found on this network. Check that it is on with "
                                + "Nickel Sales open, or type its address.", true);
                    }
                });
            });
        });

        save.setOnAction(e -> {
            String ip = address.getText() == null ? "" : address.getText().trim();
            if (!LocalAddress.isValid(ip)) {
                show(modal, result, "Type the Main POS's address as four numbers, e.g. 192.168.1.20. Nothing was "
                        + "changed.", true);
                return;
            }
            busy.run();
            show(modal, result, "Checking " + ip + "...", false);
            String before = terminals.mainPosAddress().orElse("");
            background("settings-check-main", () -> {
                String message;
                boolean error;
                try {
                    new MainPosClient(ip, ApiServer.port(), key).verifyMainPos();
                    boolean changed = terminals.updateAddress(PosTerminalRepository.MAIN_ID, ip);
                    if (changed) {
                        screen.app().audit().log("MAIN_POS_ADDRESS_CHANGED target=T01 address="
                                + (before.isBlank() ? "none" : before) + ">" + ip, screen.user().getUserId());
                    }
                    screen.app().find(SyncRuntime.class).ifPresent(SyncRuntime::syncNow);
                    message = changed ? "Saved. This computer now syncs with the Main POS at " + ip + "."
                            : "This is already the Main POS's address, and it answers.";
                    error = false;
                } catch (MainPosClient.MainPosException ex) {
                    message = ex.getMessage() + " Nothing was changed: check the address and try again.";
                    error = true;
                } catch (RuntimeException ex) {
                    message = shared.UiErrors.message(ex, "The address could not be saved. Nothing was changed.");
                    error = true;
                }
                String text = message;
                boolean isError = error;
                Platform.runLater(() -> {
                    done.run();
                    if (!isError) {
                        saved.setText("Now: " + ip);
                    }
                    show(modal, result, text, isError);
                });
            });
        });

        modal.card().getChildren().addAll(Modal.title("Change Main POS IP Address"),
                Modal.subtitle("This computer sends its sales and changes to the Main POS at this address and gets "
                        + "the other registers' from it. If the router gave the Main POS a new address, find it or "
                        + "type it, then Check and Save."),
                saved, line, result, Modal.buttons(close, save));
        modal.showAndWait();
    }

    private static void show(Modal modal, Label label, String text, boolean error) {
        label.setText(text);
        label.setStyle("-fx-font-size: 15px; -fx-text-fill: " + (error ? Theme.ERROR : Theme.SECONDARY_TEXT) + ";");
        label.setManaged(true);
        label.setVisible(true);
        modal.sizeToFit();
    }

    private static void background(String name, Runnable work) {
        Thread t = new Thread(work, name);
        t.setDaemon(true);
        t.start();
    }
}
