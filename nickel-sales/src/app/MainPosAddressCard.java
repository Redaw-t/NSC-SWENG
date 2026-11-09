package app;

import app.install.LocalAddress;
import app.install.PosTerminalRepository;
import auth.permission.Permission;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import shared.Styles;
import shared.Theme;
import sync.api.ApiServer;
import sync.api.MainPosClient;
import sync.discovery.Discovery;

import java.util.Optional;

/**
 * Settings &gt; General on an Additional POS (Admin only): the Main POS's address this computer syncs
 * with. Sync finds the Main POS by itself when the router gives it a new address; this card is for when
 * it can't (e.g. the network blocks the search). A new address is saved only after the check passes:
 * it must answer as the Main POS (T01) and accept this store's API key. Otherwise nothing changes.
 */
final class MainPosAddressCard {

    private MainPosAddressCard() { }

    /** The card, or empty on the Main POS, for non-admins, or if this PC isn't set up. */
    static Optional<VBox> create(ScreenContext screen) {
        PosTerminalRepository terminals = new PosTerminalRepository();
        String key;
        String current;
        try {
            if (!screen.app().authz().checkAccess(screen.user(), Permission.MANAGE_SYSTEM)) {
                return Optional.empty();
            }
            Optional<String> me = terminals.thisTerminalId();
            if (me.isEmpty() || PosTerminalRepository.MAIN_ID.equals(me.get())) {
                return Optional.empty();
            }
            key = terminals.apiKey().orElse(null);
            current = terminals.mainPosAddress().orElse("");
        } catch (RuntimeException e) {
            return Optional.empty();   // terminal settings can't be read: nothing to change here
        }
        if (key == null) {
            return Optional.empty();
        }
        return Optional.of(build(screen, terminals, key, current));
    }

    /** The card for an Additional POS that syncs with the Main POS at {@code current} using {@code key}. */
    static VBox build(ScreenContext screen, PosTerminalRepository terminals, String key, String current) {
        Label title = new Label("Main POS");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label note = new Label("This computer sends its sales and changes to the Main POS at this address and gets "
                + "the other terminals' from it. It finds the Main POS by itself if the address changes; if it "
                + "can't, find or type the new address here.");
        note.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        note.setWrapText(true);

        TextField address = new TextField(current);
        address.setPromptText("e.g. 192.168.1.20");
        Styles.input(address);
        address.setPrefWidth(260);
        Button find = new Button("Find Automatically");
        Styles.primaryButton(find);
        Button save = new Button("Check and Save");
        Styles.primaryButton(save);
        Label result = new Label();
        result.setWrapText(true);
        result.setStyle("-fx-font-size: 14px;");

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
            show(result, "Looking for the Main POS on the network...", false);
            background("settings-find-main", () -> {
                Optional<String> found = Discovery.find(ApiServer.port(), key, SyncRuntime.FIND_WAIT);
                Platform.runLater(() -> {
                    done.run();
                    if (found.isPresent()) {
                        address.setText(found.get());
                        show(result, "Found the Main POS at " + found.get() + ". Click Check and Save to use it.", false);
                    } else {
                        show(result, "The Main POS was not found on the network. Check that it is on with Nickel "
                                + "Sales open, or type its address.", true);
                    }
                });
            });
        });

        save.setOnAction(e -> {
            String ip = address.getText() == null ? "" : address.getText().trim();
            if (!LocalAddress.isValid(ip)) {
                show(result, "Type the Main POS's address as four numbers, e.g. 192.168.1.20. Nothing was changed.", true);
                return;
            }
            busy.run();
            show(result, "Checking " + ip + "...", false);
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
                    show(result, text, isError);
                });
            });
        });

        HBox controls = new HBox(12, address, find, save);
        controls.setAlignment(Pos.CENTER_LEFT);
        Region line = new Region();
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        VBox.setMargin(line, new Insets(16, 0, 14, 0));
        VBox words = new VBox(4, title, note);
        HBox.setHgrow(words, Priority.ALWAYS);
        VBox card = new VBox(words, line, controls, result);
        VBox.setMargin(result, new Insets(10, 0, 0, 0));
        Styles.card(card);
        card.setPadding(new Insets(22, 28, 22, 28));
        return card;
    }

    private static void show(Label label, String text, boolean error) {
        label.setText(text);
        label.setStyle("-fx-font-size: 14px; -fx-text-fill: " + (error ? Theme.ERROR : Theme.SECONDARY_TEXT) + ";");
    }

    private static void background(String name, Runnable work) {
        Thread t = new Thread(work, name);
        t.setDaemon(true);
        t.start();
    }
}
