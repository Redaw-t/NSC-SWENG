package app.install;

import auth.ui.LoginScreen;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import auth.account.AccountException;
import auth.account.FirstAdminService;
import auth.account.PassPolicy;
import javafx.scene.control.PasswordField;
import shared.ApiKey;
import shared.Theme;
import shared.UiErrors;
import sync.api.ApiServer;
import sync.api.MainPosClient;
import system.service.StoreDetailsService;
import system.service.SystemException;
import pos.receipt.StoreInfo;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * INSTALLATION SETUP: shown instead of the login page until this computer is set up
 * ({@link PosTerminalRepository#needsSetup()}). Same design as the login page: logo, big title, pill
 * fields and blue pill buttons, drawn for 1920 x 1080 and scaled to the window. The steps:
 *
 * <ol>
 *   <li><b>Main POS or Additional POS?</b> (a drop-down)</li>
 *   <li><b>API key</b> ({@link ApiKey}): Main POS "Register API Key", Additional POS "Enter the API Key".
 *       The key the developer provided; the terminals send it on every API call to trust each other.</li>
 *   <li><b>Main POS</b>: this computer is T01; its IP address is detected (shown dimmed, not typed), then
 *       <b>create the admin account</b>:
 *       the store admin's first and last name, then (on the next screen) the username made from them, as
 *       Add user does (e.g. JCubillan), and a password. The store's first user, NSC-00001, with every role.
 *       The admin is saved, then <b>Store Register</b>: the store details printed on receipts (name,
 *       address, proprietor, TIN, phone), saved in the StoreDetails log as performed by that admin
 *       (NSC-00001). Only then is T01's address saved and this computer set up. Or <b>Additional POS</b>:
 *       type the Main POS's IP address; the Main POS (its API) answers with this computer's terminal ID
 *       (T02, T03 ...) and adds it to its own list; then T01's address and this computer's own row are
 *       saved here. It gets the store details with the store's data in the next step.</li>
 *   <li><b>Additional POS: Syncing Data</b>: a loading screen while the Main POS sends its whole store
 *       database (never its nickelsales_appconfig) and it is loaded here ({@link InitialSync}). Only then
 *       is this computer saved as set up; TRY AGAIN repeats a failed sync.</li>
 *   <li><b>Done</b>: shows which terminal this computer is, then goes on to the login page.</li>
 * </ol>
 */
public final class InstallationSetupScreen {

    // Same sizes and styles as auth.ui.LoginScreen, so the screens look alike.
    private static final double DESIGN_WIDTH = 1920;
    private static final double DESIGN_HEIGHT = 1080;
    private static final double FIELD_WIDTH = 400;
    private static final double FIELD_HEIGHT = 50;
    private static final double BUTTON_HEIGHT = 46;
    private static final double LOGO_WIDTH = 168;

    private static final String FIELD_STYLE =
            "-fx-background-color: " + Theme.SURFACE + "; -fx-background-radius: 25; "
                    + "-fx-border-color: " + Theme.BORDER + "; -fx-border-width: 2; -fx-border-radius: 25; "
                    + "-fx-padding: 0 22 0 22; -fx-font-size: 20px; "
                    + "-fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-prompt-text-fill: " + Theme.SECONDARY_TEXT + "; "
                    + "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;";
    private static final String FIELD_FOCUSED_STYLE =
            FIELD_STYLE.replace("-fx-border-color: " + Theme.BORDER, "-fx-border-color: " + Theme.PRIMARY_BLUE);
    /** A read-only value (e.g. Terminal ID T01): the same pill on the app's grey. */
    private static final String FIELD_READ_ONLY_STYLE =
            FIELD_STYLE.replace("-fx-background-color: " + Theme.SURFACE, "-fx-background-color: " + Theme.BORDER)
                    .replace("-fx-text-fill: " + Theme.PRIMARY_TEXT, "-fx-text-fill: " + Theme.SECONDARY_TEXT);

    private static final String BUTTON_STYLE =
            "-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-background-radius: 23; "
                    + "-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold; -fx-cursor: hand;";
    private static final String BUTTON_HOVER_STYLE = BUTTON_STYLE.replace(Theme.PRIMARY_BLUE, Theme.SECONDARY_BLUE);
    private static final String MAIN_POS = "Main POS";
    private static final String ADDITIONAL_POS = "Additional POS";

    /**
     * The Main / Additional drop-down: the same white pill as the fields, and a rounded list like the
     * app's other drop-downs (white, light border, soft blue hover, chosen row tinted).
     */
    private static final String COMBO_CSS = "data:text/css;base64," + java.util.Base64.getEncoder().encodeToString(
            String.join("\n",
                    ".setup-combo { -fx-background-color: " + Theme.SURFACE + "; -fx-background-insets: 0; "
                            + "-fx-background-radius: 25; -fx-border-color: " + Theme.BORDER + "; -fx-border-width: 2; "
                            + "-fx-border-radius: 25; -fx-padding: 0 10 0 10; -fx-font-size: 20px; -fx-cursor: hand; }",
                    ".setup-combo:focused, .setup-combo:showing { -fx-border-color: " + Theme.PRIMARY_BLUE + "; }",
                    // the chosen value, centred (left padding = the arrow's width, so it sits in the middle of the pill)
                    ".setup-combo > .list-cell { -fx-background-color: transparent; -fx-text-fill: " + Theme.PRIMARY_TEXT
                            + "; -fx-alignment: center; -fx-padding: 0 0 0 30; }",
                    ".setup-combo > .arrow-button { -fx-background-color: transparent; -fx-padding: 0 14 0 8; }",
                    ".setup-combo > .arrow-button > .arrow { -fx-background-color: " + Theme.PRIMARY_TEXT + "; }",
                    // the list: sizes in em, so they follow the font size set to match the page's scale
                    ".combo-box-popup > .list-view { -fx-background-color: " + Theme.SURFACE + "; -fx-background-insets: 0; "
                            + "-fx-background-radius: 0.6em; -fx-border-color: " + Theme.BORDER + "; -fx-border-radius: 0.6em; "
                            + "-fx-padding: 0.3em; -fx-effect: null; }",
                    ".combo-box-popup > .list-view .list-cell { -fx-background-color: transparent; "
                            + "-fx-background-radius: 0.35em; -fx-padding: 0.5em 0 0.5em 0; -fx-alignment: center; "
                            + "-fx-text-fill: " + Theme.PRIMARY_TEXT + "; }",
                    ".combo-box-popup > .list-view .list-cell:hover { -fx-background-color: rgba(36, 87, 166, 0.08); }",
                    ".combo-box-popup > .list-view .list-cell:selected { -fx-background-color: rgba(36, 87, 166, 0.14); "
                            + "-fx-font-weight: bold; }")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String BACK_STYLE =
            "-fx-background-color: transparent; -fx-text-fill: " + Theme.PRIMARY_BLUE + "; -fx-font-size: 16px; "
                    + "-fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 4 10 4 10;";

    private final PosTerminalRepository terminals;
    private final FirstAdminService firstAdmin;     // Main POS: creates the admin account (NSC-00001)
    private final StoreDetailsService storeDetails; // Main POS: the store details, logged as performed by the admin
    private final Consumer<String> onDone;
    private final StackPane view = new StackPane();
    private final VBox step = new VBox();          // the part under the title that changes per step
    private final Label errorLabel = new Label();

    private boolean mainPos;                        // the choice made in step 1
    private boolean roleChosen;                     // so Back shows the earlier choice again
    private String apiKey;                          // set once step 2 accepts it
    private String mainIp;                          // Main POS: set once step 3 accepts it
    private String adminFirst;                      // Main POS: the store admin's names (kept for Back)
    private String adminLast;
    private String adminLogin;                      // Main POS: the admin's username, once the account is saved
    private boolean storeRegistered;                // Main POS: the store details are saved (a retry skips them)

    /**
     * @param onDone called with this computer's terminal ID (T01, T02 ...) once it is set up;
     *               normally: build the app for that terminal and show the login page
     */
    public InstallationSetupScreen(PosTerminalRepository terminals, FirstAdminService firstAdmin,
                                   StoreDetailsService storeDetails, Consumer<String> onDone) {
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.firstAdmin = Objects.requireNonNull(firstAdmin, "firstAdmin");
        this.storeDetails = Objects.requireNonNull(storeDetails, "storeDetails");
        this.onDone = Objects.requireNonNull(onDone, "onDone");
        buildFrame();
        showRoleStep();
    }

    public Parent getView() {
        return view;
    }

    /* -------------------------------- frame -------------------------------- */

    private void buildFrame() {
        Label title = new Label("INSTALLATION SETUP");
        title.setStyle("-fx-font-size: 64px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label subtitle = new Label("POS & Inventory Management System");
        subtitle.setStyle("-fx-font-size: 28px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");

        errorLabel.setWrapText(true);
        // a fixed width and "as tall as it needs", so a long message wraps onto more lines instead of ending in "..."
        errorLabel.setPrefWidth(FIELD_WIDTH + 80);
        errorLabel.setMaxWidth(FIELD_WIDTH + 80);
        errorLabel.setMinHeight(Region.USE_PREF_SIZE);
        errorLabel.setAlignment(Pos.CENTER);
        errorLabel.setTextAlignment(TextAlignment.CENTER);
        errorLabel.setStyle("-fx-text-fill: " + Theme.ERROR + "; -fx-font-size: 16px;");

        step.setAlignment(Pos.TOP_CENTER);
        VBox form = new VBox();
        form.setAlignment(Pos.CENTER);
        ImageView logo = loadLogo();
        if (logo != null) {
            form.getChildren().addAll(logo, gap(4));
        }
        form.getChildren().addAll(title, gap(5), subtitle, gap(58), step);

        view.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + "; -fx-font-family: '" + Theme.FONT_FAMILY + "';");
        view.setAlignment(Pos.CENTER);
        view.getChildren().add(form);
        scaleToWindow(form);
    }

    /** Replaces the step part with {@code nodes}, clears the error and focuses {@code focus}. */
    private void showStep(Node focus, Node... nodes) {
        hideError();
        step.getChildren().setAll(nodes);
        if (focus != null) {
            Platform.runLater(focus::requestFocus);
        }
    }

    /* ------------------------- 1. Main or Additional ------------------------- */

    private void showRoleStep() {
        ComboBox<String> role = new ComboBox<>();
        role.getItems().setAll(MAIN_POS, ADDITIONAL_POS);
        role.setPromptText("Select");
        if (roleChosen) {
            role.setValue(mainPos ? MAIN_POS : ADDITIONAL_POS);
        }
        // "Select" in grey like the fields' placeholders; the choice itself in normal dark text.
        role.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                boolean none = empty || item == null;
                setText(none ? role.getPromptText() : item);
                setStyle("-fx-text-fill: " + (none ? Theme.SECONDARY_TEXT : Theme.PRIMARY_TEXT) + ";");
            }
        });
        role.getStyleClass().add("setup-combo");
        // The page is drawn at 1920 px and scaled to the window, but the list opens in its own window,
        // which is not: give it the drop-down's on-screen width and text scaled the same way.
        role.setOnShowing(e -> fitListToBox(role));
        role.getStylesheets().add(COMBO_CSS);
        role.setPrefSize(FIELD_WIDTH, FIELD_HEIGHT);
        role.setMinSize(FIELD_WIDTH, FIELD_HEIGHT);
        role.setMaxSize(FIELD_WIDTH, FIELD_HEIGHT);

        Button next = primary("CONTINUE");
        next.setOnAction(e -> {
            if (role.getValue() == null) {
                showError("Choose Main POS or Additional POS.");
                role.requestFocus();
                return;
            }
            mainPos = MAIN_POS.equals(role.getValue());
            roleChosen = true;
            showApiKeyStep();
        });
        showStep(role, intro("Is this the Main POS or an Additional POS?"), gap(30), role, gap(12), errorLabel,
                gap(29), next);
    }

    /** Makes the open list exactly as wide as the drop-down on screen, with text at the same scale. */
    private static void fitListToBox(ComboBox<String> box) {
        if (box.getSkin() instanceof javafx.scene.control.skin.ComboBoxListViewSkin<?> skin
                && skin.getPopupContent() instanceof javafx.scene.control.ListView<?> list) {
            javafx.geometry.Bounds onScreen = box.localToScreen(box.getBoundsInLocal());
            if (onScreen == null) {
                return;
            }
            double width = onScreen.getWidth();
            double scale = width / FIELD_WIDTH;
            list.setMinWidth(width);
            list.setPrefWidth(width);
            list.setMaxWidth(width);
            list.setStyle("-fx-font-size: " + Math.round(20 * scale * 10) / 10.0 + "px;");
        }
    }

    /* ------------------------------ 2. API key ------------------------------ */

    /**
     * Main POS: "Register API Key" (the key it will check on every call from the other terminals).
     * Additional POS: "Enter the API Key" (sent to the Main POS, which must have the same one).
     */
    private void showApiKeyStep() {
        TextField key = field("e.g. NSC-XXXXX-XXXXX-XXXXX-XXXXX");
        if (apiKey != null) {
            key.setText(apiKey);
        }
        Button next = primary("CONTINUE");
        Runnable submit = () -> {
            if (key.getText() == null || key.getText().isBlank()) {
                showError("Enter the API key the developer provided.");
                return;
            }
            if (!ApiKey.isValid(key.getText())) {
                showError("That API key is not right. Check it with the developer and type it again.");
                key.selectAll();
                key.requestFocus();
                return;
            }
            apiKey = ApiKey.normalize(key.getText());
            if (mainPos) {
                showMainStep();
            } else {
                showAdditionalStep();
            }
        };
        next.setOnAction(e -> submit.run());
        key.setOnAction(e -> submit.run());
        Label heading = mainPos ? stepTitle("Register API Key") : stepTitle("Enter the API Key");
        Label hint = mainPos ? note("Use the API key the developer provided. The other terminals must use the same key.")
                : note("The same API key the Main POS was registered with.");
        showStep(key, heading, gap(6), hint, gap(30), key, gap(12), errorLabel, gap(29), next,
                gap(16), back(this::showRoleStep));
    }

    /* ------------------------------ 3a. Main POS ----------------------------- */

    private void showMainStep() {
        TextField terminal = readOnly(PosTerminalRepository.MAIN_ID);
        TextField ip = thisComputersIp("IP Address, e.g. 192.168.1.20");   // detected: dimmed, not typed
        Button next = primary("CONTINUE");
        Runnable submit = () -> {
            String address = checkedIp(ip, "this computer's");
            if (address == null) {
                return;
            }
            mainIp = address;
            showAdminStep();   // nothing is saved until the admin account is created
        };
        next.setOnAction(e -> submit.run());
        ip.setOnAction(e -> submit.run());
        showStep(ip.isEditable() ? ip : next, intro("Set up this computer as the Main POS"), gap(30),
                caption("Terminal ID"), gap(6), terminal, gap(22),
                caption("This computer's IP Address"), gap(6), ip, gap(12), errorLabel, gap(29), next,
                gap(16), back(this::showApiKeyStep));
    }

    /* ------------------------- 3a-2. Main POS: admin ------------------------- */

    /**
     * Main POS, admin account part 1: the store admin's first and last name. The username is made from
     * them (as in Manage users > Add user) and shown on the next screen; nothing is saved yet.
     */
    private void showAdminStep() {
        TextField first = field("First Name");
        TextField last = field("Last Name");
        first.setText(orEmpty(adminFirst));
        last.setText(orEmpty(adminLast));
        Button next = primary("CONTINUE");
        Runnable submit = () -> {
            String username;
            try {
                username = firstAdmin.usernameFor(first.getText(), last.getText());
            } catch (AccountException e) {
                showError(e.getMessage());
                return;
            } catch (RuntimeException e) {
                System.err.println("[SETUP] " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
                showError(UiErrors.message(e, "The users could not be checked. Check that MySQL is running and "
                        + "that setup.bat has created the databases, then try again."));
                return;
            }
            adminFirst = first.getText().trim();
            adminLast = last.getText().trim();
            showAdminPasswordStep(username);
        };
        next.setOnAction(e -> submit.run());
        first.setOnAction(e -> submit.run());
        last.setOnAction(e -> submit.run());
        showStep(first, stepTitle("Create Admin Account"), gap(6),
                note("The store admin: the store's first account, with every "
                        + "role. Use it to sign in and add the other users."), gap(30),
                caption("First Name"), gap(6), first, gap(22), caption("Last Name"), gap(6), last, gap(12),
                errorLabel, gap(29), next, gap(16), back(this::showMainStep));
    }

    /**
     * Main POS, admin account part 2: the username made from the names (shown, not typed) and the
     * password. Saves the admin, then goes on to Store Register ({@link #showStoreStep}).
     */
    private void showAdminPasswordStep(String username) {
        TextField login = readOnly(username);
        PasswordField password = passwordField("Password");
        PasswordField confirm = passwordField("Confirm Password");
        Button next = primary("CREATE ACCOUNT");
        Button back = back(this::showAdminStep);
        Runnable submit = () -> {
            next.setDisable(true);
            back.setDisable(true);
            try {
                adminLogin = firstAdmin.create(adminFirst, adminLast, password.getText(), confirm.getText())
                        .getLoginName();
            } catch (AccountException e) {
                showError(e.getMessage());
                password.requestFocus();
                next.setDisable(false);
                back.setDisable(false);
                return;
            } catch (RuntimeException e) {
                System.err.println("[SETUP] " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
                showError(UiErrors.message(e, "The setup could not be saved. Check that MySQL is running and that "
                        + "setup.bat has created the databases, then try again."));
                next.setDisable(false);
                back.setDisable(false);
                return;
            }
            System.out.println("[SETUP] admin " + adminLogin + " (" + FirstAdminService.ADMIN_ID + ") created");
            showStoreStep();
        };
        next.setOnAction(e -> submit.run());
        password.setOnAction(e -> submit.run());
        confirm.setOnAction(e -> submit.run());
        showStep(password, stepTitle("Create Admin Account"), gap(6),
                note(adminFirst + " " + adminLast + " signs in with this username. Choose the password."), gap(30),
                caption("Username"), gap(6), login, gap(22),
                caption("Password"), gap(6), password, gap(12), confirm, gap(8),
                note(PassPolicy.REQUIREMENTS), gap(8), errorLabel, gap(20), next, gap(16), back);
    }

    /* ---------------------- 3a-3. Main POS: store register --------------------- */

    /**
     * Main POS, after the admin account: the store details printed at the top of every receipt. Saved
     * as the first row of the StoreDetails log, performed by the admin just created (NSC-00001); then
     * this computer is saved as T01 at {@link #mainIp}, and setup is done. Prefilled with the details
     * in use (Nickel Sales Center's own unless the settings file has others). No Back: the admin
     * account is already saved; if setup is closed here, it starts again next time.
     */
    private void showStoreStep() {
        StoreInfo now = storeDetails.current();
        TextField name = field("Store Name");
        TextField address = field("Address");
        TextField proprietor = field("Proprietor");
        TextField tin = field("TIN, e.g. 000-000-000-00000");
        TextField phone = field("Phone");
        name.setText(now.name());
        address.setText(now.address());
        proprietor.setText(now.proprietor());
        tin.setText(now.tin());
        phone.setText(now.phone());
        Button next = primary("REGISTER STORE");
        Runnable submit = () -> {
            next.setDisable(true);
            try {
                if (!storeRegistered) {
                    storeDetails.registerAtInstallation(FirstAdminService.ADMIN_ID, name.getText(), address.getText(),
                            proprietor.getText(), tin.getText(), phone.getText());
                    storeRegistered = true;
                }
                terminals.completeMainSetup(mainIp, computerName(), apiKey);
            } catch (SystemException e) {
                showError(e.getMessage());
                next.setDisable(false);
                return;
            } catch (RuntimeException e) {
                System.err.println("[SETUP] " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
                showError(UiErrors.message(e, "The setup could not be saved. Check that MySQL is running and that "
                        + "setup.bat has created the databases, then try again."));
                next.setDisable(false);
                return;
            }
            System.out.println("[SETUP] this computer is the Main POS (T01) at " + mainIp + "; admin " + adminLogin
                    + " (" + FirstAdminService.ADMIN_ID + "); store details registered");
            showDoneStep(PosTerminalRepository.MAIN_ID, "This computer is the Main POS. Other terminals connect "
                    + "to it at " + mainIp + ". Sign in as " + adminLogin + " with the password you chose. "
                    + "An admin can change the store details in Settings > General > System Configuration > Store Details.");
        };
        next.setOnAction(e -> submit.run());
        for (TextField f : new TextField[] {name, address, proprietor, tin, phone}) {
            f.setOnAction(e -> submit.run());
        }
        showStep(name, stepTitle("Store Register"), gap(6),
                note("Printed at the top of every receipt. Only the store name is required; leave a box empty to "
                        + "leave that line off."), gap(26),
                labelled("Store Name", name), gap(12), labelled("Address", address), gap(12),
                labelled("Proprietor", proprietor), gap(12), labelled("TIN", tin), gap(12),
                labelled("Phone", phone), gap(12), errorLabel, gap(22), next);
    }

    /** A field with its name on the left, so a long form fits on the page. */
    private static HBox labelled(String text, TextField field) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        label.setMinWidth(130);
        label.setPrefWidth(130);
        label.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(16, label, field);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new javafx.geometry.Insets(0, 146, 0, 0));   // keeps the fields centred under the title
        return row;
    }

    /* --------------------------- 3b. Additional POS -------------------------- */

    private void showAdditionalStep() {
        TextField mainIp = field("Main POS IP Address, e.g. 192.168.1.20");
        TextField myIp = thisComputersIp("IP Address, e.g. 192.168.1.21");   // detected: dimmed, not typed
        Button next = primary("CONTINUE");
        Button back = back(this::showApiKeyStep);
        Runnable submit = () -> {
            String main = checkedIp(mainIp, "the Main POS's");
            if (main == null) {
                return;
            }
            String mine = checkedIp(myIp, "this computer's");
            if (mine == null) {
                return;
            }
            if (main.equals(mine)) {
                showError("The Main POS's IP address is this computer's own. Type the Main POS's address, "
                        + "or go back and choose MAIN POS.");
                return;
            }
            hideError();
            next.setDisable(true);
            back.setDisable(true);
            next.setText("CONNECTING...");
            String name = computerName();
            Thread call = new Thread(() -> {
                try {
                    String id = new MainPosClient(main, ApiServer.port(), apiKey).requestTerminalId(mine, name);
                    System.out.println("[SETUP] the Main POS at " + main + " gave this computer terminal ID " + id);
                    // next: copy the store's data; this computer counts as set up only once that is done
                    Platform.runLater(() -> showSyncStep(main, id, mine, name));
                } catch (MainPosClient.MainPosException e) {
                    Platform.runLater(() -> failed(e.getMessage(), next, back));
                } catch (RuntimeException e) {
                    System.err.println("[SETUP] " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
                    Platform.runLater(() -> failed(UiErrors.message(e, "The Main POS could not give a terminal ID. "
                            + "Try again."), next, back));
                }
            }, "setup-register");
            call.setDaemon(true);
            call.start();
        };
        next.setOnAction(e -> submit.run());
        mainIp.setOnAction(e -> submit.run());
        myIp.setOnAction(e -> submit.run());
        Label found = new Label("Looking for the Main POS on the network...");
        found.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        showStep(mainIp, intro("Connect this computer to the Main POS"), gap(30),
                caption("Main POS IP Address"), gap(6), mainIp, gap(4), found, gap(18),
                caption("This computer's IP Address"), gap(6), myIp, gap(12), errorLabel, gap(29), next,
                gap(16), back);
        findMainPos(mainIp, found);
    }

    /**
     * Looks for this store's Main POS on the network (sync.discovery.MainPosScanner) and fills in its
     * address if the box is still empty. The address can still be typed or changed.
     */
    private void findMainPos(TextField mainIp, Label found) {
        String key = apiKey;
        Thread look = new Thread(() -> {
            java.util.Optional<String> ip = sync.discovery.MainPosScanner.find(ApiServer.port(), key,
                    app.SyncRuntime.FIND_LIMIT);
            Platform.runLater(() -> {
                if (ip.isPresent() && mainIp.getText().isBlank()) {
                    mainIp.setText(ip.get());
                    found.setText("Found the Main POS on the network. Change it if it's not the right one.");
                } else if (ip.isPresent()) {
                    found.setText("The Main POS was found at " + ip.get() + ".");
                } else {
                    found.setText("The Main POS was not found on the network: type its IP address.");
                }
            });
        }, "setup-find-main");
        look.setDaemon(true);
        look.start();
    }

    /* --------------------- 3b-2. Additional POS: first sync -------------------- */

    /**
     * The loading screen "Syncing data": the Main POS sends its whole store database (users, products,
     * stock, sales ...; never its nickelsales_appconfig) and it is loaded into this computer's own.
     * Only then is this computer saved as set up (terminal ID, Main POS address, API key), so if the
     * sync fails, TRY AGAIN repeats it, and closing the app shows setup again next time.
     */
    private void showSyncStep(String mainIp, String terminalId, String myIp, String name) {
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize(72, 72);
        spinner.setMaxSize(72, 72);
        Label status = note("Copying the store's data from the Main POS at " + mainIp
                + ": users, products, stock and sales. Keep both computers on.");
        Button retry = primary("TRY AGAIN");
        retry.setVisible(false);
        retry.setManaged(false);
        Label id = new Label("Terminal " + terminalId);
        id.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_BLUE + ";");
        showStep(null, stepTitle("Syncing Data"), gap(10), id, gap(24), spinner, gap(24), status, gap(12),
                errorLabel, gap(20), retry);

        Runnable sync = () -> {
            hideError();
            spinner.setVisible(true);
            retry.setVisible(false);
            retry.setManaged(false);
            status.setText("Copying the store's data from the Main POS at " + mainIp
                    + ": users, products, stock and sales. Keep both computers on.");
            Thread work = new Thread(() -> {
                java.nio.file.Path copy = null;
                try {
                    copy = java.nio.file.Files.createTempFile("nickelsales-sync-", ".sql.gz");
                    MainPosClient client = new MainPosClient(mainIp, ApiServer.port(), apiKey);
                    long startsAt = client.downloadSnapshot(copy);
                    String mainName = client.ping();   // the Main POS's computer name, kept with its address
                    Platform.runLater(() -> status.setText("Saving the data on this computer..."));
                    InitialSync.Result loaded = InitialSync.load(copy);
                    // From here on this PC syncs with the Main POS, starting where its copy of the data ends.
                    terminals.completeAdditionalSetup(mainIp, mainName, terminalId, myIp, name, apiKey, startsAt);
                    System.out.println("[SETUP] first sync done: " + loaded.tables() + " tables from the Main POS at "
                            + mainIp + "; this computer is " + terminalId);
                    Platform.runLater(() -> showDoneStep(terminalId, "This computer is an Additional POS of the Main "
                            + "POS at " + mainIp + ". The store's data has been copied: sign in with the same "
                            + "username and password as on the Main POS."));
                } catch (MainPosClient.MainPosException e) {
                    Platform.runLater(() -> syncFailed(e.getMessage(), spinner, status, retry));
                } catch (IllegalArgumentException e) {   // the copy was cut off or is not a store copy
                    Platform.runLater(() -> syncFailed(e.getMessage() + " Nothing was changed. Try again.",
                            spinner, status, retry));
                } catch (Exception e) {
                    System.err.println("[SETUP] sync: " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
                    Platform.runLater(() -> syncFailed(UiErrors.message(e instanceof RuntimeException r ? r
                            : new RuntimeException(e), "The data could not be saved on this computer. Check that "
                            + "MySQL is running, then try again."), spinner, status, retry));
                } finally {
                    if (copy != null) {
                        try {
                            java.nio.file.Files.deleteIfExists(copy);
                        } catch (IOException ignored) {
                            // a temp file; Windows cleans it up later
                        }
                    }
                }
            }, "setup-sync");
            work.setDaemon(true);
            work.start();
        };
        retry.setOnAction(e -> sync.run());
        sync.run();
    }

    private void syncFailed(String message, javafx.scene.control.ProgressIndicator spinner, Label status, Button retry) {
        spinner.setVisible(false);
        status.setText("The data was not copied yet.");
        showError(message);
        retry.setVisible(true);
        retry.setManaged(true);
        retry.requestFocus();
    }

    private void failed(String message, Button next, Button back) {
        showError(message);
        next.setText("CONTINUE");
        next.setDisable(false);
        back.setDisable(false);
    }

    /* -------------------------------- 4. Done -------------------------------- */

    private void showDoneStep(String terminalId, String detail) {
        Label id = new Label("Terminal " + terminalId);
        id.setStyle("-fx-font-size: 40px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_BLUE + ";");
        Button next = primary("CONTINUE");
        next.setOnAction(e -> onDone.accept(terminalId));
        showStep(next, intro("Setup complete"), gap(18), id, gap(12), note(detail), gap(40), next);
    }

    /* ------------------------------- helpers -------------------------------- */

    /**
     * This computer's IP address: the detected one, dimmed and locked (nothing to type). Only if none
     * can be detected (no network found) is it a normal field to type in, so setup is never stuck.
     */
    private static TextField thisComputersIp(String prompt) {
        String detected = LocalAddress.detect();
        if (detected == null) {
            return field(prompt);
        }
        return readOnly(detected);
    }

    /** The IP address in {@code field} if it is valid; otherwise shows why and returns null. */
    private String checkedIp(TextField field, String whose) {
        String text = field.getText() == null ? "" : field.getText().trim();
        if (text.isEmpty()) {
            showError("Enter " + whose + " IP address.");
            field.requestFocus();
            return null;
        }
        if (!LocalAddress.isValid(text)) {
            showError("That IP address doesn't look right. Use four numbers from 0 to 255, e.g. 192.168.1.20.");
            field.requestFocus();
            field.selectAll();
            return null;
        }
        return text;
    }

    /** This computer's Windows name, so the Main POS recognises it if it asks again; null if unknown. */
    private static String computerName() {
        return shared.ComputerName.get();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Label intro(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 18px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return label;
    }

    /** A step's own heading, e.g. "Register API Key". */
    private static Label stepTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        return label;
    }

    /** A small grey line under a choice or a result. */
    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setPrefWidth(FIELD_WIDTH + 80);          // wraps onto more lines rather than being cut short
        label.setMaxWidth(FIELD_WIDTH + 80);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setAlignment(Pos.CENTER);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        return label;
    }

    /** A small grey name above a field, lined up with the text inside the pill. */
    private static Label caption(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + Theme.SECONDARY_TEXT
                + "; -fx-padding: 0 0 0 22;");
        label.setMaxWidth(FIELD_WIDTH);
        label.setPrefWidth(FIELD_WIDTH);
        return label;
    }

    private static TextField field(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        size(field);
        field.setStyle(FIELD_STYLE);
        field.focusedProperty().addListener((obs, was, now) -> field.setStyle(now ? FIELD_FOCUSED_STYLE : FIELD_STYLE));
        return field;
    }

    /** A password field, the same pill as {@link #field}. */
    private static PasswordField passwordField(String prompt) {
        PasswordField field = new PasswordField();
        field.setPromptText(prompt);
        size(field);
        field.setStyle(FIELD_STYLE);
        field.focusedProperty().addListener((obs, was, now) -> field.setStyle(now ? FIELD_FOCUSED_STYLE : FIELD_STYLE));
        return field;
    }

    private static TextField readOnly(String value) {
        TextField field = new TextField(value);
        field.setEditable(false);
        field.setFocusTraversable(false);
        size(field);
        field.setStyle(FIELD_READ_ONLY_STYLE);
        return field;
    }

    private static void size(TextField field) {
        field.setPrefWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_WIDTH);
        field.setMinHeight(FIELD_HEIGHT);
        field.setPrefHeight(FIELD_HEIGHT);
        field.setMaxHeight(FIELD_HEIGHT);
    }

    private static Button primary(String text) {
        Button b = pill(text, BUTTON_STYLE, BUTTON_HOVER_STYLE);
        b.setDefaultButton(true);
        return b;
    }

    private static Button pill(String text, String style, String hover) {
        Button b = new Button(text);
        b.setPrefSize(FIELD_WIDTH, BUTTON_HEIGHT);
        b.setMinSize(FIELD_WIDTH, BUTTON_HEIGHT);
        b.setMaxSize(FIELD_WIDTH, BUTTON_HEIGHT);
        b.setStyle(style);
        b.hoverProperty().addListener((obs, was, now) -> b.setStyle(now ? hover : style));
        return b;
    }

    private static Button back(Runnable action) {
        Button b = new Button("‹  Back");
        b.setStyle(BACK_STYLE);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Scales the 1920 x 1080 design to the window (never below half or above double size). */
    private void scaleToWindow(Region content) {
        Runnable apply = () -> {
            double scale = Math.min(view.getWidth() / DESIGN_WIDTH, view.getHeight() / DESIGN_HEIGHT);
            scale = Math.max(0.5, Math.min(2.0, scale));
            content.setScaleX(scale);
            content.setScaleY(scale);
        };
        view.widthProperty().addListener((obs, was, now) -> apply.run());
        view.heightProperty().addListener((obs, was, now) -> apply.run());
    }

    /** The login page's logo, trimmed the same way; null if the file is not on the classpath. */
    private static ImageView loadLogo() {
        try (InputStream in = InstallationSetupScreen.class.getResourceAsStream(LoginScreen.LOGO_RESOURCE)) {
            if (in == null) {
                return null;
            }
            Image image = new Image(in);
            ImageView logo = new ImageView(image);
            if (image.getWidth() == 600 && image.getHeight() == 600) {
                logo.setViewport(new Rectangle2D(126, 91, 351, 403));
            }
            logo.setFitWidth(LOGO_WIDTH);
            logo.setPreserveRatio(true);
            logo.setSmooth(true);
            return logo;
        } catch (IOException e) {
            return null;
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private static Region gap(double height) {
        Region spacer = new Region();
        spacer.setMinHeight(height);
        spacer.setPrefHeight(height);
        spacer.setMaxHeight(height);
        return spacer;
    }
}
