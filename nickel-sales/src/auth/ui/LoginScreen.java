package auth.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Consumer;

import auth.login.AuthenticationException;
import auth.login.AuthenticationService;
import auth.login.LoginResult;
import auth.login.InactiveUserException;
import auth.login.LockedUserException;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import shared.Theme;
import shared.UiErrors;
import shared.Modal;


/**
 * LoginScreen: logo, "NICKEL SALES CENTER" title, pill-shaped Username and
 * Password fields and a blue LOGIN pill button, in the app's palette and font
 * (see {@link Theme}).
 *
 * Calls {@link AuthenticationService#login} and reports the outcome:
 *  - success: passes the {@link LoginResult} to {@code onLoginSuccess}. This
 *    screen does NOT choose a dashboard; role routing belongs to the AUTHZ role.
 *  - failure: shows the exception's user-ready message under the fields and
 *    clears the password field.
 *
 * "Username" is only the on-screen label: the value typed is the UserLoginName
 * (e.g. JCubillan), which is the login id in the authentication module.
 *
 * The password field has a SHOW/HIDE toggle and warns when Caps Lock is on.
 *
 * The layout is drawn for a 1920 x 1080 window and scaled to fit the real window.
 *
 * The logo is optional: put it at {@link #LOGO_RESOURCE} on the classpath
 * (nickel-sales/resources/images/nickel-logo-ui.png) and it appears above the title.
 */
public class LoginScreen {

    /** Classpath location of the logo image. If missing, the screen simply has no logo. */
    public static final String LOGO_RESOURCE = "/images/nickel-logo-ui.png";

    // The design is 1920 x 1080; everything below is in those pixels and scaled to the window.
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
    // Same field with a blue border, so keyboard focus stays visible.
    private static final String FIELD_FOCUSED_STYLE =
            FIELD_STYLE.replace("-fx-border-color: " + Theme.BORDER, "-fx-border-color: " + Theme.PRIMARY_BLUE);
    // Password fields leave room on the right for the SHOW/HIDE toggle.
    private static final String PASSWORD_PADDING = "-fx-padding: 0 90 0 22;";

    private static final String TOGGLE_STYLE =
            "-fx-background-color: transparent; -fx-text-fill: " + Theme.PRIMARY_BLUE + "; "
                    + "-fx-font-size: 14px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 4 8 4 8;";

    private static final String BUTTON_STYLE =
            "-fx-background-color: " + Theme.PRIMARY_BLUE + "; -fx-background-radius: 23; "
                    + "-fx-text-fill: white; -fx-font-size: 26px; -fx-font-weight: bold; -fx-cursor: hand;";
    private static final String BUTTON_HOVER_STYLE =
            BUTTON_STYLE.replace(Theme.PRIMARY_BLUE, Theme.SECONDARY_BLUE);

    private final AuthenticationService auth;
    private final Consumer<LoginResult> onLoginSuccess;

    private final TextField userIdField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    /** Plain-text twin of passwordField, shown instead of it while "SHOW" is on. Same text. */
    private final TextField visiblePasswordField = new TextField();
    private final ToggleButton showPasswordToggle = new ToggleButton("SHOW");
    private final Label capsLockLabel = new Label("Caps Lock is on");
    private Scene watchedScene; // scene whose focus changes we listen to (the router reuses one scene)
    private final Button loginButton = new Button("LOGIN");
    private final Label errorLabel = new Label();
    private final StackPane view = new StackPane();

    public LoginScreen(AuthenticationService auth, Consumer<LoginResult> onLoginSuccess) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.onLoginSuccess = Objects.requireNonNull(onLoginSuccess, "onLoginSuccess");
        buildView();
    }

    /** The screen's root node; put it in a Scene. */
    public Parent getView() {
        return view;
    }

    /** Clears both fields and any error message. Call this when returning here after logout. */
    public void reset() {
        userIdField.clear();
        passwordField.clear();
        showPasswordToggle.setSelected(false); // never leave a password showing for the next person
        hideError();
    }

    /**
     * Shows a message under the fields, e.g. why the user was sent back here.
     * Call after {@link #reset()}, which clears it.
     */
    public void showMessage(String message) {
        showError(message);
    }

    /** Puts the cursor in the Username field. Call after the screen is shown. */
    public void focusUserId() {
        userIdField.requestFocus();
    }

    private void buildView() {
        Label title = new Label("NICKEL SALES CENTER");
        title.setStyle("-fx-font-size: 64px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label subtitle = new Label("POS & Inventory Management System");
        subtitle.setStyle("-fx-font-size: 28px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label signIn = new Label("Sign in to continue");
        signIn.setStyle("-fx-font-size: 18px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");

        userIdField.setPromptText("Username");
        passwordField.setPromptText("Password");
        visiblePasswordField.setPromptText("Password");
        styleField(userIdField);
        styleField(passwordField, PASSWORD_PADDING);
        styleField(visiblePasswordField, PASSWORD_PADDING);
        StackPane passwordBox = buildPasswordBox();

        capsLockLabel.setStyle("-fx-text-fill: " + Theme.WARNING + "; -fx-font-size: 15px; -fx-font-weight: bold;");
        setCapsLockWarning(false);
        installCapsLockWarning();

        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(FIELD_WIDTH);
        errorLabel.setAlignment(Pos.CENTER);
        errorLabel.setTextAlignment(TextAlignment.CENTER);
        errorLabel.setStyle("-fx-text-fill: " + Theme.ERROR + "; -fx-font-size: 16px;");
        hideError();

        loginButton.setPrefSize(FIELD_WIDTH, BUTTON_HEIGHT);
        loginButton.setMinSize(FIELD_WIDTH, BUTTON_HEIGHT);
        loginButton.setMaxSize(FIELD_WIDTH, BUTTON_HEIGHT);
        loginButton.setStyle(BUTTON_STYLE);
        loginButton.hoverProperty().addListener((obs, was, hover) ->
                loginButton.setStyle(hover ? BUTTON_HOVER_STYLE : BUTTON_STYLE));
        loginButton.setDefaultButton(true);
        loginButton.setOnAction(e -> submit());
        // Pressing Enter in either field also submits.
        userIdField.setOnAction(e -> {
            // Enter after typing a username but no password yet: go to the password field first.
            boolean hasUser = !userIdField.getText().trim().isEmpty();
            boolean noPassword = passwordField.getText() == null || passwordField.getText().isEmpty();
            if (hasUser && noPassword) {
                focusPassword();
            } else {
                submit();
            }
        });
        passwordField.setOnAction(e -> submit());
        visiblePasswordField.setOnAction(e -> submit());

        VBox form = new VBox();
        form.setAlignment(Pos.CENTER);
        ImageView logo = loadLogo();
        if (logo != null) {
            form.getChildren().addAll(logo, gap(4));
        }
        form.getChildren().addAll(title, gap(5), subtitle, gap(58), signIn, gap(30),
                userIdField, gap(33), passwordBox, gap(12), capsLockLabel, errorLabel, gap(29), loginButton);

        view.setStyle("-fx-background-color: " + Theme.APP_BACKGROUND + "; -fx-font-family: '" + Theme.FONT_FAMILY + "';");
        view.setAlignment(Pos.CENTER);
        view.getChildren().add(form);
        scaleToWindow(form);
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

    private void styleField(TextInputControl field) {
        styleField(field, "");
    }

    /** {@code extraStyle} is appended to the normal style (later CSS rules win). */
    private void styleField(TextInputControl field, String extraStyle) {
        field.setPrefWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_WIDTH);
        field.setMinHeight(FIELD_HEIGHT);
        field.setPrefHeight(FIELD_HEIGHT);
        field.setMaxHeight(FIELD_HEIGHT);
        field.setStyle(FIELD_STYLE + extraStyle);
        field.focusedProperty().addListener((obs, was, now) ->
                field.setStyle((now ? FIELD_FOCUSED_STYLE : FIELD_STYLE) + extraStyle));
    }

    /**
     * The password field and its plain-text twin stacked on top of each other,
     * sharing the same text, with a SHOW/HIDE toggle inside the pill on the right.
     * Only one of the two fields is visible at a time.
     */
    private StackPane buildPasswordBox() {
        visiblePasswordField.textProperty().bindBidirectional(passwordField.textProperty());
        visiblePasswordField.setVisible(false);

        showPasswordToggle.setStyle(TOGGLE_STYLE);
        showPasswordToggle.setFocusTraversable(false); // Tab goes username -> password -> LOGIN
        showPasswordToggle.selectedProperty().addListener((obs, was, show) -> {
            boolean hadFocus = passwordHasCursor();
            passwordField.setVisible(!show);
            visiblePasswordField.setVisible(show);
            showPasswordToggle.setText(show ? "HIDE" : "SHOW");
            if (hadFocus || show) {
                focusPassword();
            }
        });

        StackPane box = new StackPane(passwordField, visiblePasswordField, showPasswordToggle);
        box.setMaxWidth(FIELD_WIDTH);
        StackPane.setAlignment(showPasswordToggle, Pos.CENTER_RIGHT);
        StackPane.setMargin(showPasswordToggle, new Insets(0, 18, 0, 0));
        return box;
    }

    /** Focuses whichever password field is showing, with the cursor at the end. */
    private void focusPassword() {
        TextField shown = showPasswordToggle.isSelected() ? visiblePasswordField : passwordField;
        shown.requestFocus();
        shown.positionCaret(shown.getText() == null ? 0 : shown.getText().length());
    }

    /**
     * Shows "Caps Lock is on" under the password while it has focus and Caps Lock
     * is on. Re-checked whenever a key is pressed or released anywhere on this
     * screen (so pressing Caps Lock updates it straight away) and when the
     * password field gains or loses focus.
     */
    private void installCapsLockWarning() {
        view.addEventFilter(KeyEvent.KEY_PRESSED, e -> updateCapsLockWarning());
        view.addEventFilter(KeyEvent.KEY_RELEASED, e -> updateCapsLockWarning());
        passwordField.focusedProperty().addListener((obs, was, now) -> updateCapsLockWarning());
        visiblePasswordField.focusedProperty().addListener((obs, was, now) -> updateCapsLockWarning());
        // Also follow the scene's focus owner: it changes even while the window
        // itself is not the active window.
        view.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null && scene != watchedScene) {
                watchedScene = scene;
                scene.focusOwnerProperty().addListener((o, was, now) -> updateCapsLockWarning());
            }
        });
    }

    private void updateCapsLockWarning() {
        setCapsLockWarning(passwordHasCursor() && isCapsLockOn());
    }

    /** True when the cursor is in the password field (either the hidden or the shown one). */
    private boolean passwordHasCursor() {
        Scene scene = view.getScene();
        if (scene == null) {
            return false;
        }
        return scene.getFocusOwner() == passwordField || scene.getFocusOwner() == visiblePasswordField;
    }

    /** False when the platform cannot report Caps Lock, so the warning simply never shows. */
    private static boolean isCapsLockOn() {
        try {
            return Platform.isKeyLocked(KeyCode.CAPS).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void setCapsLockWarning(boolean on) {
        capsLockLabel.setVisible(on);
        capsLockLabel.setManaged(on);
    }

    /** The logo, trimmed of its transparent border; null if the file is not on the classpath. */
    private ImageView loadLogo() {
        try (InputStream in = LoginScreen.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                return null;
            }
            Image image = new Image(in);
            ImageView logo = new ImageView(image);
            // The supplied 600 x 600 PNG has empty space around the mark (it fills x 126-476, y 91-493).
            // Show only the mark. A differently cropped file is simply scaled to the same width.
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

    private void submit() {
        String userId = userIdField.getText() == null ? "" : userIdField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();

        if (userId.isEmpty() || password.isEmpty()) {
            showError("Enter your username and password.");
            return;
        }

        LoginResult result = attemptLogin(userId, password);
        if (result != null) {
            reset();
            // Outside attemptLogin's try/catch, so a bug in the next screen is
            // never shown to the cashier as a "login failed" message.
            onLoginSuccess.accept(result);
        }
    }

    /** Runs the login; on any failure shows a message and returns null. */
    private LoginResult attemptLogin(String userId, String password) {
        loginButton.setDisable(true);
        try {
            LoginResult result = auth.login(userId, password);
            if (result.session() == null) {
                // Only happens if no SessionManager was wired into AuthenticationService.
                showError("Login worked, but no session could be started. Contact the system administrator.");
                passwordField.clear();
                return null;
            }
            return result;
        } catch (AuthenticationException e) {
            showError(e.getMessage());
            passwordField.clear();
            focusPassword();
            if ((e instanceof LockedUserException || e instanceof InactiveUserException) && view.getScene() != null) {
                Modal.notice(view.getScene().getWindow(),
                        e instanceof LockedUserException ? "Account locked" : "Account inactive", e.getMessage());
            }
            return null;
        } catch (RuntimeException e) {
            // Database or other unexpected problem: never show a stack trace on screen,
            // and never let the user in.
            showError(UiErrors.message(e,
                    "Something went wrong while logging in. Try again, or contact the system administrator."));
            passwordField.clear();
            return null;
        } finally {
            loginButton.setDisable(false);
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