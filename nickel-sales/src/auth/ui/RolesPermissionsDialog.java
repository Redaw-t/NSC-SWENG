package auth.ui;

import auth.contract.UserRole;
import auth.permission.AuthorizationService;
import auth.permission.Permission;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.geometry.HPos;
import javafx.stage.Window;
import shared.Modal;
import shared.Styles;
import shared.Theme;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The "Roles & Permissions" pop-up: a read-only table of what each role may do. */
final class RolesPermissionsDialog {

    private static final List<UserRole> ROLES = List.of(UserRole.ADMIN, UserRole.INVENTORY_STAFF, UserRole.CASHIER);

    private RolesPermissionsDialog() { }

    static void show(Window owner, AuthorizationService authz) {
        Modal modal = new Modal(owner, 760);

        GridPane grid = new GridPane();
        ColumnConstraints first = new ColumnConstraints();
        first.setPercentWidth(46);
        grid.getColumnConstraints().add(first);
        for (int i = 0; i < ROLES.size(); i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(18);
            c.setHalignment(HPos.CENTER);
            grid.getColumnConstraints().add(c);
        }

        Label heading = new Label("Permission");
        Styles.detailKey(heading);
        grid.add(heading, 0, 0);
        for (int c = 0; c < ROLES.size(); c++) {
            Label pill = UserUi.rolePill(ROLES.get(c));
            GridPane.setHalignment(pill, HPos.CENTER);
            grid.add(pill, c + 1, 0);
        }
        GridPane.setMargin(heading, new Insets(0, 0, 12, 0));

        int row = 1;
        for (Permission permission : Permission.values()) {
            grid.add(cell(describe(permission), false, false), 0, row);
            for (int c = 0; c < ROLES.size(); c++) {
                Set<Permission> allowed = authz.permissionsForUser(EnumSet.of(ROLES.get(c)));
                boolean yes = allowed.contains(permission);
                grid.add(cell(yes ? "\u2713" : "\u2013", true, yes), c + 1, row);
            }
            row++;
        }

        Button close = new Button("Close");
        Styles.modalButton(close, Theme.INFORMATION, "white");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> modal.close());

        modal.card().getChildren().addAll(Modal.title("Roles & Permissions"),
                Modal.subtitle("What each role can do. A person can hold more than one role and gets all of them."),
                grid, Modal.buttons(close));
        modal.showAndWait();
    }

    /** One grid cell with a thin line under it. */
    private static Label cell(String text, boolean centred, boolean yes) {
        Label label = new Label(text);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(centred ? Pos.CENTER : Pos.CENTER_LEFT);
        String colour = centred ? (yes ? Theme.SUCCESS : Theme.SECONDARY_TEXT) : Theme.PRIMARY_TEXT;
        label.setStyle("-fx-font-size: " + (centred ? 17 : 15) + "px; -fx-font-weight: " + (centred && yes ? "bold" : "normal")
                + "; -fx-text-fill: " + colour + "; -fx-padding: 10 0 10 0; -fx-border-color: transparent transparent "
                + Theme.BORDER + " transparent; -fx-border-width: 0 0 1 0;");
        return label;
    }

    private static String describe(Permission permission) {
        return switch (permission) {
            case PROCESS_SALE -> "Process sales (POS)";
            case MANAGE_PRODUCTS -> "Manage products";
            case UPDATE_INVENTORY -> "Update inventory";
            case VIEW_SALES_REPORTS -> "Sales reports";
            case VIEW_INVENTORY_REPORTS -> "Inventory reports";
            case MANAGE_USERS -> "Manage users";
            case MANAGE_SYSTEM -> "System & sync";
        };
    }
}
