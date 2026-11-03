package app;

import auth.contract.User;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import shared.Styles;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The frame around a screen: a left sidebar (logo and name, Dashboard, POS, Products,
 * Inventory, Reports, Users, Settings, who is logged in, Logout) with the screen itself
 * on the right.
 *
 * <p>The router wraps the Dashboard, Settings and every module whose
 * {@link AppModule#usesShell()} is true in this. The sidebar lists Dashboard and Settings
 * for everyone and the other screens only if the user may open them ({@code availableTitles});
 * the router checks the permission again when one is clicked. The sidebar is open on the
 * Dashboard (so it is open after login) and on Settings, and collapses to a thin strip on every other screen;
 * the hamburger button opens or closes it by hand until the next screen change.
 */
public final class AppShell {

    private static final double EXPANDED_WIDTH = 260;
    private static final double COLLAPSED_WIDTH = 64;
    private static final String LOGO_RESOURCE = "/images/nickel-logo-icon.png";

    /** Title of the Settings entry; the router opens it as a screen, not as a module. */
    public static final String SETTINGS = "Settings";
    /** The \"System & sync\" module (see {@link Modules}); it is reached from Settings. */
    public static final String SYSTEM = "System & sync";

    /** One sidebar row: its label, and the {@link AppModule#title()} it opens (null = Dashboard, SETTINGS = Settings). */
    private record Entry(String label, String moduleTitle) { }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("Dashboard", null),
            new Entry("POS", "Point of Sale"),
            new Entry("Products", "Products"),
            new Entry("Inventory", "Inventory"),
            new Entry("Reports", "Reports"),
            new Entry("Users", "Manage users"),
            new Entry("Settings", SETTINGS));

    private AppShell() { }

    /**
     * @param activeTitle     the title of the module being shown (highlighted in the sidebar);
     *                        null for the Dashboard, {@link #SETTINGS} for Settings
     * @param availableTitles titles of the modules this user may open
     * @param content         the screen itself
     * @param goHome          Dashboard was clicked
     * @param openModule      another entry was clicked; called with that module's title
     *                        (or {@link #SETTINGS})
     * @param logout          Logout was clicked
     * @param startOpen       true to show the sidebar open, false collapsed (the router opens it
     *                        on the Dashboard only)
     */
    public static Parent wrap(User user, String activeTitle, Set<String> availableTitles, Parent content,
                              Runnable goHome, Consumer<String> openModule, Runnable logout,
                              boolean startOpen) {
        VBox nav = new VBox();
        for (Entry entry : ENTRIES) {
            boolean dashboard = entry.moduleTitle() == null;
            boolean settings = SETTINGS.equals(entry.moduleTitle());
            if (!dashboard && !settings && !availableTitles.contains(entry.moduleTitle())) {
                continue;
            }
            // Settings also stays highlighted on the System & sync screen, which is opened from it.
            boolean active = dashboard ? activeTitle == null
                    : settings ? SETTINGS.equals(activeTitle) || SYSTEM.equals(activeTitle)
                    : entry.moduleTitle().equals(activeTitle);
            boolean here = dashboard ? activeTitle == null : entry.moduleTitle().equals(activeTitle);
            Button item = new Button(entry.label());
            item.setMaxWidth(Double.MAX_VALUE);
            Styles.navItem(item, active);
            item.setOnAction(e -> {
                if (here) {
                    return;   // already on this screen
                }
                if (dashboard) {
                    goHome.run();
                } else {
                    openModule.accept(entry.moduleTitle());
                }
            });
            nav.getChildren().add(item);
        }

        // hamburger: three bars
        VBox bars = new VBox(5);
        for (int i = 0; i < 3; i++) {
            Region bar = new Region();
            Styles.burgerLine(bar);
            bars.getChildren().add(bar);
        }
        Button burger = new Button();
        burger.setGraphic(bars);
        Styles.burger(burger);
        burger.setAccessibleText("Show or hide the menu");

        // header: logo and name on the left, hamburger on the right
        HBox brand = new HBox(8);
        brand.setAlignment(Pos.CENTER_LEFT);
        ImageView logo = loadLogo();
        if (logo != null) {
            brand.getChildren().add(logo);
        }
        Label brandName = new Label("NICKEL SALES CENTER");
        Styles.brandName(brandName);
        brand.getChildren().add(brandName);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox top = new HBox(brand, headerSpacer, burger);
        Styles.sidebarHeader(top);

        // footer: who is logged in, then Logout
        Label name = new Label("Logged in as " + user.getFullName());
        Styles.userName(name);
        name.setWrapText(true);
        name.setTextAlignment(TextAlignment.CENTER);
        name.setAlignment(Pos.CENTER);
        name.setMaxWidth(Double.MAX_VALUE);
        Button logoutButton = new Button("Logout");
        Styles.logout(logoutButton);
        logoutButton.setMaxWidth(Double.MAX_VALUE);
        logoutButton.setOnAction(e -> logout.run());
        VBox footer = new VBox(8, name, logoutButton);
        footer.setPadding(new Insets(16, 14, 20, 14));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox sidebar = new VBox(top, nav, spacer, footer);
        Styles.sidebar(sidebar);

        boolean[] expanded = {startOpen};
        Runnable applyState = () -> {
            boolean open = expanded[0];
            brand.setVisible(open);
            brand.setManaged(open);
            headerSpacer.setVisible(open);
            headerSpacer.setManaged(open);
            nav.setVisible(open);
            nav.setManaged(open);
            footer.setVisible(open);
            footer.setManaged(open);
            top.setAlignment(open ? Pos.CENTER_LEFT : Pos.CENTER);
            top.setPadding(open ? new Insets(12, 10, 12, 14) : new Insets(12, 0, 12, 0));
            setWidth(sidebar, open ? EXPANDED_WIDTH : COLLAPSED_WIDTH);
        };
        applyState.run();
        burger.setOnAction(e -> {
            expanded[0] = !expanded[0];
            applyState.run();
        });

        BorderPane host = new BorderPane();
        Styles.content(host);
        host.setPadding(new Insets(32, 40, 24, 40));
        host.setCenter(content);

        BorderPane root = new BorderPane();
        Styles.shellRoot(root);
        root.setLeft(sidebar);
        root.setCenter(host);
        return root;
    }

    /** The small logo in the header, or null if the image is missing (the name is then shown alone). */
    private static ImageView loadLogo() {
        try (InputStream in = AppShell.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                return null;
            }
            ImageView view = new ImageView(new Image(in));
            view.setFitWidth(34);
            view.setFitHeight(34);
            view.setPreserveRatio(true);
            return view;
        } catch (IOException e) {
            return null;
        }
    }

    private static void setWidth(Region region, double width) {
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }
}