package auth.ui;

import auth.account.AccountException;
import auth.account.UserAdminService;
import auth.contract.User;
import auth.contract.UserRole;
import auth.contract.UserStatus;
import auth.permission.AuthorizationService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import shared.Modal;
import shared.SearchPicker;
import shared.Styles;
import shared.TablePager;
import shared.Theme;
import shared.UiErrors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "Users" tab: summary cards, search and Role / Status filters, the user table (avatar,
 * role pills, status switch, last sign-in, row actions) and the pager. Every action goes through
 * UserAdminService, which checks the admin's permission again and writes the audit log.
 */
final class UsersPane {

    private static final double ROW_HEIGHT = 50;
    private static final double HEADER_HEIGHT = 52;   // matches Styles.table(...)
    private static final int RECENT_DAYS = 30;
    private static final String FALLBACK = "Something went wrong. Try again, or contact the system administrator.";

    private enum RoleFilter {
        ALL("All Roles", null),
        ADMIN("Admin", UserRole.ADMIN),
        INVENTORY("Inventory", UserRole.INVENTORY_STAFF),
        CASHIER("Cashier", UserRole.CASHIER);

        private final String label;
        private final UserRole role;

        RoleFilter(String label, UserRole role) {
            this.label = label;
            this.role = role;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum StatusFilter {
        ALL("All Statuses", null),
        ACTIVE("Active", UserStatus.ACTIVE),
        INACTIVE("Inactive", UserStatus.INACTIVE),
        LOCKED("Locked", UserStatus.LOCKED);

        private final String label;
        private final UserStatus status;

        StatusFilter(String label, UserStatus status) {
            this.label = label;
            this.status = status;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private interface Action {
        void run() throws AccountException;
    }

    private final UserAdminService admin;
    private final AuthorizationService authz;
    private final String sessionId;
    private final Runnable onBack;

    private final UserUi.StatCard totalCard = new UserUi.StatCard("Total Users");
    private final UserUi.StatCard activeCard = new UserUi.StatCard("Active");
    private final UserUi.StatCard adminCard = new UserUi.StatCard("Administrators");
    private final UserUi.StatCard attentionCard = new UserUi.StatCard("Needs Attention");
    private final TextField search = new TextField();
    private final SearchPicker<RoleFilter> roleFilter = new SearchPicker<>(false, false);
    private final SearchPicker<StatusFilter> statusFilter = new SearchPicker<>(false, false);
    private final TableView<User> table = new TableView<>();
    private final TablePager pager = new TablePager(this::showPage);
    private final Label message = new Label();
    private final VBox view = new VBox(16);

    private List<User> loaded = List.of();
    private List<User> filtered = List.of();

    UsersPane(UserAdminService admin, AuthorizationService authz, String sessionId, Runnable onBack) {
        this.admin = admin;
        this.authz = authz;
        this.sessionId = sessionId;
        this.onBack = onBack;

        // ---- title row
        VBox titles = new VBox(2, UserUi.pageTitle("User Management"),
                UserUi.pageSubtitle("Control who can sign in and what they can access."));
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Button roles = UserUi.outlineButton("Roles & Permissions");
        roles.setOnAction(e -> RolesPermissionsDialog.show(owner(), this.authz));
        Button add = new Button("+ Add User");
        Styles.primaryButton(add);
        add.setOnAction(e -> addUser());
        HBox buttons = new HBox(12, roles, add);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        HBox titleRow = new HBox(titles, titleSpacer, buttons);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // ---- summary cards
        HBox cards = new HBox(16, totalCard, activeCard, adminCard, attentionCard);

        // ---- filters
        search.setPromptText("Search name or username...");
        search.setPrefWidth(300);
        Styles.input(search);
        search.textProperty().addListener((obs, old, text) -> applyFilters(true));
        roleFilter.getItems().setAll(RoleFilter.values());
        roleFilter.setValue(RoleFilter.ALL);
        roleFilter.setPrefWidth(160);
        roleFilter.valueProperty().addListener((obs, old, v) -> applyFilters(true));
        statusFilter.getItems().setAll(StatusFilter.values());
        statusFilter.setValue(StatusFilter.ALL);
        statusFilter.setPrefWidth(180);
        statusFilter.valueProperty().addListener((obs, old, v) -> applyFilters(true));
        HBox filters = new HBox(12, search, roleFilter, statusFilter);
        filters.setAlignment(Pos.CENTER_LEFT);

        // ---- table
        Styles.table(table);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(ROW_HEIGHT);
        table.setMinHeight(HEADER_HEIGHT + ROW_HEIGHT * 3 + 2);
        table.setPlaceholder(new Label("No users found."));
        table.getColumns().add(UserUi.<User>nodeColumn("User", 260, 200, true, this::userCell));
        table.getColumns().add(UserUi.<User>textColumn("Username", 150, User::getLoginName));
        table.getColumns().add(UserUi.<User>nodeColumn("Role", 200, 170, false, UserUi::roleCell));
        table.getColumns().add(UserUi.<User>nodeColumn("Status", 150, 130, false, this::statusCell));
        table.getColumns().add(UserUi.<User>nodeColumn("Last Sign-in", 190, 150, false, UserUi::lastSignInCell));
        table.getColumns().add(UserUi.<User>nodeColumn("Actions", 250, 230, false, this::actionsCell));
        fitPageToHeight();

        message.setWrapText(true);
        Styles.error(message);

        view.getChildren().addAll(titleRow, cards, filters, table, message, pager.getView());
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    Parent getView() {
        return view;
    }

    /** Called every time the tab is shown. */
    void refresh() {
        reload(view.getScene() != null);
    }

    /* ------------------------------- table cells ------------------------------- */

    private Node userCell(User u) {
        Label name = new Label(u.getFullName());
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        HBox box = new HBox(12, UserUi.avatar(), name);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** The switch and its word, or a red "Locked" pill (a locked user is released with Unlock). */
    private Node statusCell(User u) {
        if (u.getStatus() == UserStatus.LOCKED) {
            Label locked = new Label("Locked");
            Styles.pill(locked, Theme.ERROR, "white");
            return locked;
        }
        boolean on = u.getStatus() == UserStatus.ACTIVE;
        Label word = new Label(on ? "Active" : "Inactive");
        word.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        HBox box = new HBox(8, UserUi.toggle(on, () -> toggleActive(u)), word);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Node actionsCell(User u) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER);
        switch (u.getStatus()) {
            case LOCKED -> {
                box.getChildren().add(rowButton("Unlock", true, () -> activate(u)));
                box.getChildren().add(rowButton("Reset Password", false, () -> resetPassword(u)));
            }
            case INACTIVE -> {
                box.getChildren().add(rowButton("Edit", false, () -> edit(u)));
                box.getChildren().add(rowButton("Reactivate", true, () -> activate(u)));
            }
            default -> {
                box.getChildren().add(rowButton("Edit", false, () -> edit(u)));
                box.getChildren().add(rowButton("Reset Password", false, () -> resetPassword(u)));
            }
        }
        return box;
    }

    private static Button rowButton(String text, boolean primary, Runnable action) {
        Button button = new Button(text);
        if (primary) {
            Styles.rowButton(button, Theme.PRIMARY_BLUE, "white");
        } else {
            Styles.rowButton(button, Theme.BORDER, Theme.PRIMARY_TEXT);
        }
        button.setOnAction(e -> action.run());
        return button;
    }

    /* --------------------------------- actions --------------------------------- */

    private void addUser() {
        UserFormDialog.show(owner(), null, form -> {
            User created = admin.createUser(sessionId, form.firstName(), form.lastName(),
                    form.roles(), form.password());
            return "User created.\nUsername: " + created.getLoginName();
        });
        reload(true);
    }

    private void edit(User u) {
        UserFormDialog.show(owner(), u, form -> {
            admin.updateUser(sessionId, u.getUserId(), form.firstName(), form.lastName(), form.roles());
            return null;
        });
        // If the admin just removed their own Admin role, reload() notices the lost access
        // and returns them to their (new) dashboard.
        reload(true);
    }

    private void resetPassword(User u) {
        ResetPasswordDialog.show(owner(), u, password -> admin.resetPassword(sessionId, u.getUserId(), password));
        reload(true);
    }

    /** Unlock and Reactivate: activating also clears a Locked status. */
    private void activate(User u) {
        attempt(() -> admin.setActive(sessionId, u.getUserId(), true));
    }

    /** The status switch: off needs a confirmation, on does not. */
    private void toggleActive(User u) {
        if (u.getStatus() == UserStatus.ACTIVE) {
            boolean sure = Modal.confirm(owner(), "Deactivate User",
                    "Deactivate " + u.getFullName() + "?\n\nThey will no longer be able to sign in. "
                            + "You can reactivate them at any time.", "Deactivate");
            if (!sure) {
                return;
            }
            attempt(() -> admin.setActive(sessionId, u.getUserId(), false));
        } else {
            activate(u);
        }
    }

    /** Runs the action, then reloads. A failed action shows its message under the table. */
    private void attempt(Action action) {
        try {
            action.run();
        } catch (AccountException e) {
            reload(true);                       // leaves the screen if access was lost
            message.setText(e.getMessage());    // otherwise say why it failed
            return;
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, FALLBACK));
            return;
        }
        reload(true);
    }

    /* ------------------------------ loading & paging ------------------------------ */

    /**
     * Reloads the user list. listUsers() re-checks that the current user may still manage users;
     * if not (Admin role removed, account deactivated, session expired) and {@code leaveIfDenied}
     * is set, we explain and go back to the dashboard.
     */
    private void reload(boolean leaveIfDenied) {
        if (leaveIfDenied && view.getScene() == null) {
            return;   // this screen is no longer displayed (e.g. idle logout): nothing to refresh
        }
        try {
            loaded = admin.listUsers(sessionId);
            updateCards();
            applyFilters(false);
            message.setText("");
        } catch (AccountException e) {
            if (leaveIfDenied) {
                Modal.notice(owner(), "Access changed",
                        e.getMessage() + "\n\nYou will be returned to your dashboard.");
                onBack.run();
            } else {
                message.setText(e.getMessage());
            }
        } catch (RuntimeException e) {
            message.setText(UiErrors.message(e, FALLBACK));
        }
    }

    private void updateCards() {
        Set<UserRole> rolesInUse = EnumSet.noneOf(UserRole.class);
        long admins = 0;
        long recent = 0;
        long locked = 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RECENT_DAYS);
        for (User u : loaded) {
            rolesInUse.addAll(u.getRoles());
            if (u.isActive() && u.hasRole(UserRole.ADMIN)) {
                admins++;
            }
            if (u.isActive() && u.getLastSignIn() != null && u.getLastSignIn().isAfter(cutoff)) {
                recent++;
            }
            if (u.getStatus() == UserStatus.LOCKED) {
                locked++;
            }
        }
        int roleCount = rolesInUse.size();
        totalCard.update(String.valueOf(loaded.size()), "across " + roleCount + (roleCount == 1 ? " role" : " roles"), false);
        activeCard.update(String.valueOf(recent), "signed in within " + RECENT_DAYS + " days", false);
        adminCard.update(String.valueOf(admins), "full access", false);
        attentionCard.update(String.valueOf(locked), "locked accounts", locked > 0);
    }

    /** Narrows the loaded users by the search text, Role and Status. */
    private void applyFilters(boolean backToFirstPage) {
        String text = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        RoleFilter role = roleFilter.getValue();
        StatusFilter status = statusFilter.getValue();
        List<User> result = new ArrayList<>();
        for (User u : loaded) {
            boolean textOk = text.isEmpty()
                    || u.getFullName().toLowerCase(Locale.ROOT).contains(text)
                    || u.getLoginName().toLowerCase(Locale.ROOT).contains(text)
                    || u.getUserId().toLowerCase(Locale.ROOT).contains(text);
            boolean roleOk = role == null || role.role == null || u.hasRole(role.role);
            boolean statusOk = status == null || status.status == null || u.getStatus() == status.status;
            if (textOk && roleOk && statusOk) {
                result.add(u);
            }
        }
        filtered = result;
        if (backToFirstPage) {
            pager.reset();
        }
        showPage();
    }

    private void showPage() {
        pager.setTotal(filtered.size());
        table.getItems().setAll(pager.slice(filtered));
    }

    /** Shows exactly as many rows as fit the table's height, so it never needs a scroll bar. */
    private void fitPageToHeight() {
        table.heightProperty().addListener((obs, old, height) -> Platform.runLater(() -> {
            double usable = table.getHeight() - HEADER_HEIGHT - 2;   // 2 = the table's border
            if (usable >= ROW_HEIGHT && pager.setPageSize((int) Math.floor(usable / ROW_HEIGHT))) {
                showPage();
            }
        }));
    }

    private Window owner() {
        return view.getScene() == null ? null : view.getScene().getWindow();
    }
}
