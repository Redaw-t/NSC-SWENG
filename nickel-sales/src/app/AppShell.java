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
 * The frame around a module's screen: a left sidebar (logo and name, Dashboard, POS, Products,
 * Inventory, Reports, Users, Settings, who is logged in, Logout) with the module's own screen
 * on the right.
 *
 * <p>The router wraps a module in this when {@link AppModule#usesShell()} is true. The
 * sidebar only lists screens the user may open ({@code availableTitles}); the router
 * checks the permission again when one is clicked. It starts collapsed to a thin strip;
 * the hamburger button opens and closes it.
 */
public final class AppShell {

    private static final double EXPANDED_WIDTH = 260;
    private static final double COLLAPSED_WIDTH = 64;
    private static final String LOGO_RESOURCE = "/images/nickel-logo-icon.png";

    /** One sidebar row: its label, and the {@link AppModule#title()} it opens (null = home screen). */
    private record Entry(String label, String moduleTitle) { }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("Dashboard", null),
            new Entry("POS", "Point of Sale"),
            new Entry("Products", "Products"),
            new Entry("Inventory", "Inventory"),
            new Entry("Reports", "Reports"),
            new Entry("Users", "Manage users"),
            new Entry("Settings", "System & sync"));

    private AppShell() { }

    /**
     * @param activeTitle     the title of the module being shown (highlighted in the sidebar)
     * @param availableTitles titles of the modules this user may open
     * @param content         the module's own screen
     * @param goHome          Dashboard was clicked
     * @param openModule      another entry was clicked; called with that module's title
     * @param logout          Logout was clicked
     */
    public static Parent wrap(User user, String activeTitle, Set<String> availableTitles, Parent content,
                              Runnable goHome, Consumer<String> openModule, Runnable logout) {
        VBox nav = new VBox();
        for (Entry entry : ENTRIES) {
            boolean dashboard = entry.moduleTitle() == null;
            if (!dashboard && !availableTitles.contains(entry.moduleTitle())) {
                continue;
            }
            boolean active = !dashboard && entry.moduleTitle().equals(activeTitle);
            Button item = new Button(entry.label());
            item.setMaxWidth(Double.MAX_VALUE);
            Styles.navItem(item, active);
            item.setOnAction(e -> {
                if (dashboard) {
                    goHome.run();
                } else if (!active) {
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

        boolean[] expanded = {false};   // hidden by default
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