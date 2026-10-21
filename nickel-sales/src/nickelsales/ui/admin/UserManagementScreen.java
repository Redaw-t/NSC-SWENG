package nickelsales.ui.admin;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import nickelsales.auth.account.AccountException;
import nickelsales.auth.account.UserAdminService;
import nickelsales.auth.contract.User;
import nickelsales.auth.contract.UserRole;

import java.util.function.Function;

/** Admin screen: list users, add, edit, and activate/deactivate. */
public class UserManagementScreen {

    private interface Action {
        void run() throws AccountException;
    }

    private final UserAdminService admin;
    private final String sessionId;
    private final TableView<User> table = new TableView<>();
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();

    public UserManagementScreen(UserAdminService admin, String sessionId, Runnable onBack) {
        this.admin = admin;
        this.sessionId = sessionId;

        table.getColumns().add(column("User ID", User::getUserId));
        table.getColumns().add(column("Username", User::getLoginName));
        table.getColumns().add(column("Name", User::getFullName));
        table.getColumns().add(column("Roles", u -> UserRole.toDbCodes(u.getRoles())));
        table.getColumns().add(column("Status", u -> u.getStatus().toDbString()));

        Button add = new Button("Add user");
        Button edit = new Button("Edit");
        Button toggle = new Button("Activate");
        Button back = new Button("Back");

        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        toggle.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, user) ->
                toggle.setText(user != null && user.isActive() ? "Deactivate" : "Activate"));

        add.setOnAction(e -> addUser());
        edit.setOnAction(e -> editSelected());
        toggle.setOnAction(e -> toggleSelected());
        back.setOnAction(e -> onBack.run());

        Label heading = new Label("User Management");
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        message.setWrapText(true);
        message.setStyle("-fx-text-fill: #a61c1c;");

        view.setTop(heading);
        view.setCenter(table);
        view.setBottom(new VBox(10, message, new HBox(12, add, edit, toggle, back)));
        BorderPane.setMargin(table, new Insets(16, 0, 16, 0));
        view.setPadding(new Insets(24));
        view.setStyle("-fx-background-color: white;");

        attempt(() -> { });
    }

    public Parent getView() {
        return view;
    }

    private void addUser() {
        UserFormDialog.show(owner(), null, form -> {
            User created = admin.createUser(sessionId, form.firstName(), form.lastName(),
                    form.roles(), form.password());
            return "User created.\nUsername: " + created.getLoginName();
        });
        attempt(() -> { });
    }

    private void editSelected() {
        User selected = table.getSelectionModel().getSelectedItem();
        UserFormDialog.show(owner(), selected, form -> {
            admin.updateUser(sessionId, selected.getUserId(), form.firstName(), form.lastName(), form.roles());
            return null;
        });
        attempt(() -> { });
    }

    private void toggleSelected() {
        User selected = table.getSelectionModel().getSelectedItem();
        attempt(() -> admin.setActive(sessionId, selected.getUserId(), !selected.isActive()));
    }

    /** Runs the action, reloads the table, and shows any failure under it. */
    private void attempt(Action action) {
        try {
            action.run();
            table.getItems().setAll(admin.listUsers(sessionId));
            message.setText("");
        } catch (AccountException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            message.setText("Something went wrong. Try again, or contact the system administrator.");
        }
    }

    private Window owner() {
        return view.getScene() == null ? null : view.getScene().getWindow();
    }

    private static TableColumn<User, String> column(String title, Function<User, String> value) {
        TableColumn<User, String> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        col.setPrefWidth(160);
        return col;
    }
}
