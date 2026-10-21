package nickelsales.ui.login;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.text.TextAlignment;
import nickelsales.auth.auth.AuthenticationException;
import nickelsales.auth.auth.AuthenticationService;
import nickelsales.auth.auth.LoginResult;

import java.io.InputStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * LoginScreen — Sprint 1, owned by the SESSION/UI role.
 *
 * Styled after the team's login slide: logo, "NICKEL SALES CENTER" title,
 * pill-shaped "Username" and "Password" fields, and a red LOGIN pill button.
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
 * Usage:
 * <pre>
 *   LoginScreen login = new LoginScreen(auth, result -> router.openDashboardFor(result));
 *   stage.setTitle("Nickel Sales Center - POS");
 *   stage.setScene(new Scene(login.getView(), 1280, 720));
 * </pre>
 *
 * The logo is optional: put it at {@link #LOGO_RESOURCE} on the classpath
 * (src/main/resources/images/nickel-logo.png) and it appears above the title.
 */
public class LoginScreen {

    /** Classpath location of the logo image. If missing, the screen simply has no logo. */
    public static final String LOGO_RESOURCE = "/images/nickel-logo.png";

    private static final String BLUE = "#29529a";
    private static final String RED = "#d05c5c";
    private static final String ERROR_RED = "#a61c1c";
    private static final double FIELD_WIDTH = 410;
    private static final double FIELD_HEIGHT = 52;

    private static final String FIELD_STYLE =
            "-fx-background-color: white; -fx-background-radius: 30; "
                    + "-fx-border-color: #b3b3b3; -fx-border-width: 3; -fx-border-radius: 30; "
                    + "-fx-padding: 0 20 0 20; -fx-font-size: 18px; -fx-font-weight: bold; "
                    + "-fx-prompt-text-fill: #222222; -fx-focus-color: transparent; -fx-faint-focus-color: transparent;";
    // Same field with a blue border, so keyboard focus stays visible.
    private static final String FIELD_FOCUSED_STYLE = FIELD_STYLE.replace("-fx-border-color: #b3b3b3", "-fx-border-color: " + BLUE);

    private final AuthenticationService auth;
    private final Consumer<LoginResult> onLoginSuccess;

    private final TextField userIdField = new TextField();
    private final PasswordField passwordField = new PasswordField();
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
        hideError();
    }

    /** Puts the cursor in the Username field. Call after the screen is shown. */
    public void focusUserId() {
        userIdField.requestFocus();
    }

    private void buildView() {
        Label title = new Label("NICKEL SALES CENTER");
        title.setStyle("-fx-font-family: 'Arial'; -fx-font-size: 48px; -fx-font-weight: bold; -fx-text-fill: " + BLUE + ";");

        userIdField.setPromptText("Username");
        passwordField.setPromptText("Password");
        styleField(userIdField);
        styleField(passwordField);

        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(FIELD_WIDTH);
        errorLabel.setAlignment(Pos.CENTER);
        errorLabel.setTextAlignment(TextAlignment.CENTER);
        errorLabel.setStyle("-fx-text-fill: " + ERROR_RED + "; -fx-font-size: 15px;");
        hideError();

        loginButton.setGraphic(buildUserIcon());
        loginButton.setContentDisplay(ContentDisplay.RIGHT);
        loginButton.setGraphicTextGap(28);
        loginButton.setPrefSize(240, 60);
        loginButton.setStyle("-fx-background-color: " + RED + "; -fx-background-radius: 30; "
                + "-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold; -fx-cursor: hand;");
        loginButton.setDefaultButton(true);
        loginButton.setOnAction(e -> submit());
        // Pressing Enter in either field also submits.
        userIdField.setOnAction(e -> submit());
        passwordField.setOnAction(e -> submit());

        VBox form = new VBox(20);
        form.setAlignment(Pos.CENTER);
        ImageView logo = loadLogo();
        if (logo != null) {
            form.getChildren().add(logo);
        }
        form.getChildren().addAll(title, gap(30), userIdField, passwordField, errorLabel, gap(10), loginButton);

        view.setStyle("-fx-background-color: white;");
        view.setAlignment(Pos.CENTER);
        view.getChildren().add(form);
    }

    private void styleField(TextInputControl field) {
        field.setPrefWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_WIDTH);
        field.setMinHeight(FIELD_HEIGHT);
        field.setPrefHeight(FIELD_HEIGHT);
        field.setStyle(FIELD_STYLE);
        field.focusedProperty().addListener((obs, was, now) -> field.setStyle(now ? FIELD_FOCUSED_STYLE : FIELD_STYLE));
    }

    /** A white disc with a red person glyph, drawn with shapes so no image file is needed. */
    private StackPane buildUserIcon() {
        Circle disc = new Circle(22, Color.WHITE);
        Circle head = new Circle(6.5, Color.web(RED));
        head.setTranslateY(-6);
        Ellipse body = new Ellipse(11, 7);
        body.setFill(Color.web(RED));
        body.setTranslateY(9);
        return new StackPane(disc, head, body);
    }

    private ImageView loadLogo() {
        InputStream in = LoginScreen.class.getResourceAsStream(LOGO_RESOURCE);
        if (in == null) {
            return null;
        }
        ImageView logo = new ImageView(new Image(in));
        logo.setFitWidth(200);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        return logo;
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
            passwordField.requestFocus();
            return null;
        } catch (RuntimeException e) {
            // Database or other unexpected problem: never show a stack trace on screen.
            showError("Something went wrong while logging in. Try again, or contact the system administrator.");
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
        return spacer;
    }
}