package dashboard;

import app.AppShell;
import app.ScreenContext;
import auth.contract.UserRole;
import inventory.contract.StockOverview;
import inventory.contract.StockSnapshot;
import inventory.model.StockMovement;
import inventory.service.InventoryService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import pos.contract.SaleRecord;
import product.contract.ProductLookup;
import shared.Money;
import shared.PhTime;
import shared.Quantity;
import shared.Styles;
import shared.Theme;
import system.model.ActivityDescription;
import system.model.ActivityEntry;
import system.model.ActivityFilter;
import system.service.ActivityLogService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Dashboard: the first screen after login, inside the sidebar frame. It shows one section per
 * role the user holds (a person can hold several), stacked in this order, and scrolls when they do
 * not fit:
 *
 * <ul>
 *   <li><b>Admin</b>: today's sales for the store, active products, low and
 *       out of stock; inventory alerts and recent activity.</li>
 *   <li><b>Inventory staff</b>: products, low / out of stock, stock movements today; an out-of-stock
 *       banner; inventory alerts and the latest stock movements.</li>
 *   <li><b>Cashier</b>: "My Day": their own sales, transactions, items and average
 *       sale, each against the same time yesterday; their latest sales and an hour-by-hour chart.</li>
 * </ul>
 *
 * Sections follow the user's roles (auth.contract.UserRole), not permissions: an Admin holds every
 * permission but sees only the Admin section unless also given the other roles.
 * Every card loads on its own, so one failing source shows a message in its card and the rest
 * still work. It refreshes itself every minute while shown, and on F5 or the Refresh button.
 */
public final class DashboardScreen {

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter TIME_DATE = DateTimeFormatter.ofPattern("h:mm a  M/d/yy", Locale.US);
    private static final int LIST_ROWS = 6;
    private static final int PRODUCT_LIMIT = 100_000;
    private static final double CHART_HEIGHT = 150;

    /**
     * A slim, rounded scroll bar: no arrows, no track, a soft blue thumb that widens and darkens a
     * little under the mouse.
     */
    private static final String SCROLL_CSS = String.join("\n",
            ".dash-scroll, .dash-scroll:focused { -fx-background-color: transparent; -fx-background-insets: 0; "
                    + "-fx-padding: 0; -fx-border-color: transparent; }",
            ".dash-scroll > .viewport { -fx-background-color: transparent; }",
            ".dash-scroll > .corner { -fx-background-color: transparent; }",
            ".dash-scroll .scroll-bar:vertical { -fx-background-color: transparent; -fx-pref-width: 12; "
                    + "-fx-padding: 4 3 4 3; }",
            ".dash-scroll .scroll-bar:vertical:hover { -fx-padding: 4 1 4 1; }",
            ".dash-scroll .scroll-bar .track, .dash-scroll .scroll-bar .track-background { "
                    + "-fx-background-color: transparent; -fx-border-color: transparent; }",
            ".dash-scroll .scroll-bar .thumb { -fx-background-color: rgba(36, 87, 166, 0.28); "
                    + "-fx-background-radius: 999; -fx-background-insets: 0; }",
            ".dash-scroll .scroll-bar .thumb:hover { -fx-background-color: rgba(36, 87, 166, 0.5); }",
            ".dash-scroll .scroll-bar .thumb:pressed { -fx-background-color: rgba(36, 87, 166, 0.7); }",
            ".dash-scroll .scroll-bar .increment-button, .dash-scroll .scroll-bar .decrement-button { "
                    + "-fx-background-color: transparent; -fx-padding: 0; -fx-pref-height: 0; -fx-pref-width: 0; }",
            ".dash-scroll .scroll-bar .increment-arrow, .dash-scroll .scroll-bar .decrement-arrow { "
                    + "-fx-shape: \" \"; -fx-padding: 0; }");

    private static final String INVENTORY = "Inventory";
    private static final String POS = "Point of Sale";

    /** One dashboard section per role, in this order. */
    private enum Role {
        ADMIN(UserRole.ADMIN, "Admin"),
        INVENTORY(UserRole.INVENTORY_STAFF, "Inventory"),
        CASHIER(UserRole.CASHIER, "Cashier");

        private final UserRole userRole;
        private final String heading;

        Role(UserRole userRole, String heading) {
            this.userRole = userRole;
            this.heading = heading;
        }
    }

    private final ScreenContext screen;
    private final Set<String> available;
    private final Consumer<String> open;
    private final List<Role> roles;

    private final VBox view = new VBox(24);
    private final VBox sections = new VBox(36);
    private final ScrollPane scroll = new ScrollPane();
    private final Label updated = new Label();
    private final Timeline autoRefresh = new Timeline(new KeyFrame(Duration.minutes(1), e -> load()));

    /**
     * @param available titles of the modules this user may open (for the panels' buttons)
     * @param open      opens a module by its title (the router checks the permission again)
     */
    public static Parent create(ScreenContext screen, Set<String> available, Consumer<String> open) {
        return new DashboardScreen(screen, available, open).wrapped();
    }

    private DashboardScreen(ScreenContext screen, Set<String> available, Consumer<String> open) {
        this.screen = screen;
        this.available = available;
        this.open = open;
        this.roles = rolesOf(screen);
        build();
        load();
    }

    /** The user's roles in section order; a user with none of them gets the Cashier section. */
    private static List<Role> rolesOf(ScreenContext screen) {
        List<Role> out = new ArrayList<>();
        for (Role r : Role.values()) {
            if (screen.user().hasRole(r.userRole)) {
                out.add(r);
            }
        }
        if (out.isEmpty()) {
            out.add(Role.CASHIER);
        }
        return out;
    }

    /* ------------------------------ layout ------------------------------ */

    private void build() {
        Label title = new Label(roles.equals(List.of(Role.CASHIER)) ? "My Day" : "Dashboard");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label welcome = new Label("Welcome back, " + screen.user().getFullName());
        welcome.setStyle("-fx-font-size: 16px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label date = new Label(LONG_DATE.format(PhTime.today()));
        date.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        VBox words = new VBox(4, title, welcome, date);

        Styles.muted(updated);
        Button refresh = new Button("↻  Refresh");
        Styles.rowButton(refresh, Theme.BORDER, Theme.PRIMARY_TEXT);
        refresh.setOnAction(e -> load());
        VBox right = new VBox(6, refresh, updated);
        right.setAlignment(Pos.TOP_RIGHT);

        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        HBox header = new HBox(words, fill, right);

        VBox.setVgrow(sections, Priority.ALWAYS);
        view.getChildren().addAll(header, sections);
        view.setPadding(new Insets(0, 8, 8, 0));   // room for the scroll bar

        view.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F5) {
                load();
                e.consume();
            }
        });
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        view.sceneProperty().addListener((o, was, is) -> {
            if (is == null) {
                autoRefresh.stop();   // left the dashboard
            } else {
                autoRefresh.play();
            }
        });
    }

    /**
     * Scrolls when the sections do not fit (several roles, or a small window). With one section the
     * page fills the window, so the panels stretch down to the bottom like the design.
     */
    private Parent wrapped() {
        scroll.setContent(view);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(roles.size() == 1);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent;");
        scroll.getStyleClass().add("dash-scroll");
        scroll.getStylesheets().add("data:text/css;base64,"
                + Base64.getEncoder().encodeToString(SCROLL_CSS.getBytes(StandardCharsets.UTF_8)));
        // JavaFX scrolls a long page very slowly by default: move about 3 lines per wheel notch.
        view.setOnScroll(e -> {
            double content = view.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
            if (content > 0) {
                scroll.setVvalue(scroll.getVvalue() - e.getDeltaY() * 3 / content);
                e.consume();
            }
        });
        return scroll;
    }

    /* ------------------------------ loading ------------------------------ */

    private void load() {
        double keep = scroll.getVvalue();
        Stock stock = stock();   // loaded once, shared by the sections
        List<Node> built = new ArrayList<>();
        for (Role r : roles) {
            VBox section = new VBox(20);
            if (roles.size() > 1) {
                section.getChildren().add(sectionHeading(r));
            }
            switch (r) {
                case ADMIN -> loadAdmin(section, stock);
                case INVENTORY -> loadInventory(section, stock);
                case CASHIER -> loadCashier(section);
            }
            if (roles.size() == 1) {
                VBox.setVgrow(section, Priority.ALWAYS);
            }
            built.add(section);
        }
        sections.getChildren().setAll(built);
        scroll.setVvalue(keep);   // a refresh keeps the place on the page
        updated.setText("Updated " + TIME.format(PhTime.of(Instant.now())));
    }

    /** "Admin", "Inventory" or "Cashier" above each section, when the user has several roles. */
    private static Node sectionHeading(Role r) {
        Label name = new Label(r.heading);
        name.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_BLUE + ";");
        return name;
    }

    /** The two rows of a section: number cards, an optional banner, then the panels. */
    private static void fill(VBox section, List<Node> cards, Node banner, Node... panelNodes) {
        HBox kpis = new HBox(20);
        kpis.getChildren().setAll(cards);
        HBox panels = new HBox(20, panelNodes);
        panels.setFillHeight(true);
        VBox.setVgrow(panels, Priority.ALWAYS);
        section.getChildren().add(kpis);
        if (banner != null) {
            section.getChildren().add(banner);
        }
        section.getChildren().add(panels);
    }

    private void loadAdmin(VBox section, Stock stock) {
        Sales sales = sales(null);
        Node salesCard = sales.failed ? errorCard("Today's Sales") : kpi("Today's Sales",
                "₱" + Money.format(sales.today.sales()),
                DashboardStats.change(sales.today.sales(), sales.yesterday.sales(), "sales"),
                sales.today.transactions() + (sales.today.transactions() == 1 ? " transaction" : " transactions"));
        fill(section, List.of(salesCard,
                productsCard(),
                stock.failed ? errorCard("Low Stock Items") : kpi("Low Stock Items",
                        String.valueOf(DashboardStats.count(stock.all, StockSnapshot.Level.LOW_STOCK)),
                        null, "At or below the reorder level"),
                stock.failed ? errorCard("Out of Stock Items") : kpi("Out of Stock Items",
                        String.valueOf(DashboardStats.count(stock.all, StockSnapshot.Level.OUT_OF_STOCK)),
                        null, "Nothing left to sell")),
                null, alertsPanel(stock), activityPanel());
    }

    private void loadInventory(VBox section, Stock stock) {
        Movements moves = movements();
        fill(section, List.of(
                productsCard(),
                stock.failed ? errorCard("Low Stock Items") : kpi("Low Stock Items",
                        String.valueOf(DashboardStats.count(stock.all, StockSnapshot.Level.LOW_STOCK)),
                        null, "At or below the reorder level"),
                stock.failed ? errorCard("Out of Stock Items") : kpi("Out of Stock Items",
                        String.valueOf(DashboardStats.count(stock.all, StockSnapshot.Level.OUT_OF_STOCK)),
                        null, "Nothing left to sell"),
                moves.failed ? errorCard("Stock Movements Today") : kpi("Stock Movements Today",
                        String.valueOf(moves.today),
                        DashboardStats.change(BigDecimal.valueOf(moves.today), BigDecimal.valueOf(moves.yesterday),
                                "movements"),
                        "Stock in, stock out, adjustments and sales")),
                outOfStockBanner(stock), alertsPanel(stock), movementsPanel(moves));
    }

    /** The yellow "out of stock" banner (Inventory section): what to restock first. Null when nothing is out. */
    private static Node outOfStockBanner(Stock stock) {
        long out = stock.failed ? 0 : DashboardStats.count(stock.all, StockSnapshot.Level.OUT_OF_STOCK);
        if (out == 0) {
            return null;
        }
        List<String> names = DashboardStats.alerts(stock.all).stream()
                .filter(s -> s.level() == StockSnapshot.Level.OUT_OF_STOCK).map(StockSnapshot::name)
                .limit(5).toList();
        Label banner = new Label("\u26A0  " + out + (out == 1 ? " item is" : " items are") + " out of stock: "
                + String.join(", ", names) + (out > names.size() ? " and " + (out - names.size()) + " more" : "")
                + ". Restock them so POS can sell them again.");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.PRIMARY_TEXT + "; -fx-background-color: "
                + Styles.rgba(Theme.WARNING, 0.15) + "; -fx-background-radius: 6; -fx-padding: 10 16 10 16; "
                + "-fx-border-color: " + Styles.rgba(Theme.WARNING, 0.5) + "; -fx-border-radius: 6;");
        return banner;
    }

    private void loadCashier(VBox section) {
        String me = screen.user().getUserId();
        Sales sales = sales(me);
        List<Node> cards;
        if (sales.failed) {
            cards = List.of(errorCard("My Sales Today"), errorCard("My Transactions"),
                    errorCard("Items Sold"), errorCard("Average Sale"));
        } else {
            DashboardStats.Totals t = sales.today;
            DashboardStats.Totals y = sales.yesterday;
            cards = List.of(
                    kpi("My Sales Today", "₱" + Money.format(t.sales()),
                            DashboardStats.change(t.sales(), y.sales(), "sales"), null),
                    kpi("My Transactions", String.valueOf(t.transactions()),
                            DashboardStats.change(BigDecimal.valueOf(t.transactions()),
                                    BigDecimal.valueOf(y.transactions()), "transactions"), null),
                    kpi("Items Sold", Quantity.format(t.items()),
                            DashboardStats.change(t.items(), y.items(), "items"), null),
                    kpi("Average Sale", "₱" + Money.format(t.average()), null,
                            y.transactions() == 0 ? "Per transaction today"
                                    : "₱" + Money.format(y.average()) + " by this time yesterday"));
        }
        fill(section, cards, null, myRecentPanel(sales, me), hourlyPanel(sales, me));
    }

    /* ------------------------------ data ------------------------------ */

    /** Today so far and yesterday up to the same time, for one cashier or (null) everyone. */
    private static final class Sales {
        DashboardStats.Totals today = DashboardStats.Totals.NONE;
        DashboardStats.Totals yesterday = DashboardStats.Totals.NONE;
        List<SaleRecord> todayList = List.of();
        List<SaleRecord> yesterdayList = List.of();
        boolean failed;
    }

    private Sales sales(String cashier) {
        Sales s = new Sales();
        try {
            LocalDate today = PhTime.today();
            Instant now = Instant.now();
            Instant startToday = PhTime.startOf(today);
            Instant startYesterday = PhTime.startOf(today.minusDays(1));
            Instant sameTimeYesterday = now.minus(java.time.Duration.ofDays(1));
            s.todayList = screen.app().saleHistory().findCompleted(startToday, now.plusSeconds(1));
            s.yesterdayList = DashboardStats.before(
                    screen.app().saleHistory().findCompleted(startYesterday, startToday), sameTimeYesterday);
            s.today = DashboardStats.totals(s.todayList, cashier);
            s.yesterday = DashboardStats.totals(s.yesterdayList, cashier);
        } catch (RuntimeException e) {
            s.failed = true;
        }
        return s;
    }

    private static final class Stock {
        List<StockSnapshot> all = List.of();
        boolean failed;
    }

    private Stock stock() {
        Stock s = new Stock();
        try {
            StockOverview overview = screen.app().find(StockOverview.class).orElse(null);
            if (overview == null) {
                s.failed = true;
            } else {
                s.all = overview.currentStock();
            }
        } catch (RuntimeException e) {
            s.failed = true;
        }
        return s;
    }

    private static final class Movements {
        List<StockMovement> latest = List.of();
        int today;
        int yesterday;
        boolean failed;
    }

    private Movements movements() {
        Movements m = new Movements();
        try {
            InventoryService inventory = screen.app().find(InventoryService.class).orElse(null);
            if (inventory == null) {
                m.failed = true;
                return m;
            }
            List<StockMovement> history = inventory.history(screen.sessionId(), 1000);   // newest first
            Instant now = Instant.now();
            Instant startToday = PhTime.startOf(PhTime.today());
            Instant startYesterday = PhTime.startOf(PhTime.today().minusDays(1));
            Instant sameTimeYesterday = now.minus(java.time.Duration.ofDays(1));
            for (StockMovement mv : history) {
                Instant at = mv.createdAt();
                if (!at.isBefore(startToday)) {
                    m.today++;
                } else if (!at.isBefore(startYesterday) && at.isBefore(sameTimeYesterday)) {
                    m.yesterday++;
                }
            }
            m.latest = history.subList(0, Math.min(LIST_ROWS, history.size()));
        } catch (Exception e) {
            m.failed = true;
        }
        return m;
    }

    private Node productsCard() {
        try {
            ProductLookup products = screen.app().products().orElse(null);
            if (products == null) {
                return errorCard("Active Products");
            }
            return kpi("Active Products", String.valueOf(products.search("", PRODUCT_LIMIT).size()), null,
                    "Products POS can sell");
        } catch (RuntimeException e) {
            return errorCard("Active Products");
        }
    }

    /* ------------------------------ panels ------------------------------ */

    private Node alertsPanel(Stock stock) {
        VBox rows = new VBox();
        if (stock.failed) {
            rows.getChildren().add(emptyText("Stock levels could not be loaded."));
        } else {
            List<StockSnapshot> alerts = DashboardStats.alerts(stock.all);
            if (alerts.isEmpty()) {
                rows.getChildren().add(emptyText("Everything is in stock. ✔"));
            }
            for (StockSnapshot s : alerts.subList(0, Math.min(LIST_ROWS, alerts.size()))) {
                boolean out = s.level() == StockSnapshot.Level.OUT_OF_STOCK;
                Label tag = new Label(out ? "OUT" : "LOW");
                tag.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: "
                        + (out ? Theme.ERROR : Theme.WARNING) + ";");
                String left = out ? "0 " + s.unit() + " left"
                        : Quantity.format(s.onHand()) + " " + s.unit() + " left (reorder at "
                                + Quantity.format(s.reorderLevel()) + ")";
                rows.getChildren().add(listRow(s.name(), left, tag));
            }
            if (alerts.size() > LIST_ROWS) {
                rows.getChildren().add(emptyText("+ " + (alerts.size() - LIST_ROWS) + " more"));
            }
        }
        return panel("Inventory Alerts", rows, "View Inventory", INVENTORY);
    }

    private Node activityPanel() {
        VBox rows = new VBox();
        ActivityLogService log = screen.app().find(ActivityLogService.class).orElse(null);
        if (log == null) {
            rows.getChildren().add(emptyText("The activity log is not connected in this build."));
        } else {
            try {
                LocalDate today = PhTime.today();
                List<ActivityEntry> entries = log.find(screen.sessionId(),
                        new ActivityFilter(today.minusDays(6), today, null, null, null));
                if (entries.isEmpty()) {
                    rows.getChildren().add(emptyText("No activity in the last 7 days."));
                }
                for (ActivityEntry e : entries.subList(0, Math.min(LIST_ROWS, entries.size()))) {
                    Label when = new Label(TIME_DATE.format(PhTime.of(e.createdAt())));
                    when.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
                    rows.getChildren().add(listRow(ActivityDescription.describe(e), userName(e.userId()), when));
                }
            } catch (Exception e) {
                rows.getChildren().add(emptyText("Recent activity could not be loaded."));
            }
        }
        return panel("Recent Activity", rows, "View All", AppShell.SYSTEM);
    }

    private Node movementsPanel(Movements moves) {
        VBox rows = new VBox();
        if (moves.failed) {
            rows.getChildren().add(emptyText("Stock movements could not be loaded."));
        } else {
            if (moves.latest.isEmpty()) {
                rows.getChildren().add(emptyText("No stock movements yet."));
            }
            Map<String, String> names = productNames();
            for (StockMovement m : moves.latest) {
                boolean in = m.quantity().signum() > 0;
                Label qty = new Label((in ? "+" : "") + Quantity.format(m.quantity()));
                qty.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: "
                        + (in ? Theme.SUCCESS : Theme.ERROR) + ";");
                rows.getChildren().add(listRow(names.getOrDefault(m.productId(), m.productId()),
                        m.type().label() + "  ·  " + TIME_DATE.format(PhTime.of(m.createdAt())), qty));
            }
        }
        return panel("Recent Stock Movements", rows, "View Inventory", INVENTORY);
    }

    private Node myRecentPanel(Sales sales, String me) {
        VBox rows = new VBox();
        if (sales.failed) {
            rows.getChildren().add(emptyText("Your sales could not be loaded."));
        } else {
            List<SaleRecord> latest = DashboardStats.latest(sales.todayList, me, LIST_ROWS);
            if (latest.isEmpty()) {
                rows.getChildren().add(emptyText("No sales yet today. Your first one will show here."));
            }
            for (SaleRecord s : latest) {
                Label total = new Label("₱" + Money.format(s.total()));
                total.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
                int items = s.lines().size();
                rows.getChildren().add(listRow(s.invoiceNo(),
                        TIME.format(PhTime.of(s.completedAt())) + "  ·  " + items + (items == 1 ? " line" : " lines"),
                        total));
            }
        }
        return panel("My Recent Sales", rows, "Open POS", POS);
    }

    /** Transactions per hour: today (blue) next to yesterday (grey). */
    private Node hourlyPanel(Sales sales, String me) {
        VBox body = new VBox(10);
        if (sales.failed) {
            body.getChildren().add(emptyText("Your sales could not be loaded."));
            return panel("Sales by Hour", body, null, null);
        }
        int[] today = DashboardStats.perHour(sales.todayList, me);
        int[] yesterday = DashboardStats.perHour(sales.yesterdayList, me);
        int first = 8;
        int last = Math.max(17, PhTime.of(Instant.now()).getHour());
        int max = 1;
        for (int h = 0; h < 24; h++) {
            if (today[h] > 0 || yesterday[h] > 0) {
                first = Math.min(first, h);
                last = Math.max(last, h);
            }
            max = Math.max(max, Math.max(today[h], yesterday[h]));
        }

        HBox bars = new HBox(6);
        bars.setAlignment(Pos.BOTTOM_CENTER);
        for (int h = first; h <= last; h++) {
            Region y = bar(yesterday[h], max, Theme.BORDER);
            Region t = bar(today[h], max, Theme.PRIMARY_BLUE);
            HBox pair = new HBox(2, y, t);
            pair.setAlignment(Pos.BOTTOM_CENTER);
            pair.setMinHeight(CHART_HEIGHT);
            pair.setPrefHeight(CHART_HEIGHT);
            Label hour = new Label(hourLabel(h));
            hour.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            VBox column = new VBox(4, pair, hour);
            column.setAlignment(Pos.BOTTOM_CENTER);
            HBox.setHgrow(column, Priority.ALWAYS);
            Tooltip.install(column, new Tooltip(hourLabel(h) + ":  today " + today[h] + ", yesterday " + yesterday[h]));
            bars.getChildren().add(column);
        }

        HBox legend = new HBox(18, legendItem(Theme.PRIMARY_BLUE, "Today"), legendItem(Theme.BORDER, "Yesterday"));
        legend.setAlignment(Pos.CENTER);
        body.getChildren().addAll(bars, legend);
        return panel("Transactions by Hour", body, null, null);
    }

    /* ------------------------------ building blocks ------------------------------ */

    /** A number card like the design: title, a line, the big number, and an optional note under it. */
    private static Node kpi(String title, String value, DashboardStats.Change change, String note) {
        Label name = new Label(title);
        name.setStyle("-fx-font-size: 18px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Label big = new Label(value);
        big.setStyle("-fx-font-size: 40px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        big.setMaxWidth(Double.MAX_VALUE);
        big.setAlignment(Pos.CENTER);

        VBox card = new VBox(12, name, line(), big);
        if (change != null) {
            Label c = new Label(arrow(change.direction()) + change.text());
            c.setStyle("-fx-font-size: 13px; -fx-text-fill: " + colour(change.direction()) + ";");
            c.setWrapText(true);
            c.setMaxWidth(Double.MAX_VALUE);
            c.setAlignment(Pos.CENTER);
            card.getChildren().add(c);
        }
        if (note != null) {
            Label n = new Label(note);
            n.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            n.setWrapText(true);
            n.setMaxWidth(Double.MAX_VALUE);
            n.setAlignment(Pos.CENTER);
            card.getChildren().add(n);
        }
        return card(card);
    }

    private static Node errorCard(String title) {
        return kpi(title, "—", null, "Could not be loaded. Press Refresh.");
    }

    /** A list panel: title, a line, the rows, and an optional button that opens a module. */
    private Node panel(String title, Node content, String buttonText, String moduleTitle) {
        Label name = new Label(title);
        name.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        Region push = new Region();
        VBox.setVgrow(push, Priority.ALWAYS);
        VBox box = new VBox(12, name, line(), content, push);
        if (buttonText != null && available.contains(moduleTitle)) {
            Button go = new Button(buttonText);
            Styles.primaryButton(go);
            go.setStyle(go.getStyle() + " -fx-background-radius: 999;");
            go.setPrefWidth(320);
            go.setOnAction(e -> open.accept(moduleTitle));
            StackPane holder = new StackPane(go);
            holder.setPadding(new Insets(8, 0, 0, 0));
            box.getChildren().add(holder);
        }
        box.setMinHeight(340);
        Node card = card(box);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    /** One row of a panel: main text (and a grey second line) on the left, something on the right. */
    private static Node listRow(String main, String sub, Node right) {
        Label top = new Label(main);
        top.setStyle("-fx-font-size: 15px; -fx-text-fill: " + Theme.PRIMARY_TEXT + ";");
        top.setTextOverrun(OverrunStyle.ELLIPSIS);
        top.setMinWidth(0);
        VBox left = new VBox(2, top);
        if (sub != null && !sub.isBlank()) {
            Label below = new Label(sub);
            below.setStyle("-fx-font-size: 12px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
            below.setTextOverrun(OverrunStyle.ELLIPSIS);
            below.setMinWidth(0);
            left.getChildren().add(below);
        }
        left.setMinWidth(0);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox row = new HBox(16, left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));
        row.setStyle("-fx-border-color: transparent transparent " + Styles.rgba(Theme.BORDER, 0.7)
                + " transparent; -fx-border-width: 0 0 1 0;");
        return row;
    }

    private static Label emptyText(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.SECONDARY_TEXT + "; -fx-padding: 8 0 0 0;");
        l.setWrapText(true);
        return l;
    }

    private static Node card(VBox content) {
        content.setPadding(new Insets(20, 24, 22, 24));
        Styles.card(content);
        content.setMinWidth(0);
        content.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(content, Priority.ALWAYS);
        return content;
    }

    private static Region line() {
        Region r = new Region();
        r.setMinHeight(1);
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color: " + Theme.BORDER + ";");
        return r;
    }

    private static Region bar(int value, int max, String colour) {
        Region r = new Region();
        double h = value == 0 ? 2 : Math.max(4, CHART_HEIGHT * value / max);
        r.setMinHeight(h);
        r.setPrefHeight(h);
        r.setMaxHeight(h);
        r.setMinWidth(8);
        r.setPrefWidth(14);
        r.setMaxWidth(18);
        r.setStyle("-fx-background-color: " + colour + "; -fx-background-radius: 3 3 0 0;");
        return r;
    }

    private static Node legendItem(String colour, String text) {
        Region swatch = new Region();
        swatch.setMinSize(12, 12);
        swatch.setMaxSize(12, 12);
        swatch.setStyle("-fx-background-color: " + colour + "; -fx-background-radius: 2;");
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.SECONDARY_TEXT + ";");
        HBox item = new HBox(6, swatch, l);
        item.setAlignment(Pos.CENTER);
        return item;
    }

    private static String hourLabel(int h) {
        int twelve = h % 12 == 0 ? 12 : h % 12;
        return twelve + (h < 12 ? "a" : "p");
    }

    private static String arrow(DashboardStats.Direction d) {
        return switch (d) {
            case UP, NEW -> "▲ ";
            case DOWN -> "▼ ";
            case SAME -> "— ";
        };
    }

    private static String colour(DashboardStats.Direction d) {
        return switch (d) {
            case UP, NEW -> Theme.SUCCESS;
            case DOWN -> Theme.ERROR;
            case SAME -> Theme.SECONDARY_TEXT;
        };
    }

    private String userName(String userId) {
        if (userId == null) {
            return "System";
        }
        try {
            String name = screen.app().userName(userId);
            return name == null ? userId : name;
        } catch (RuntimeException e) {
            return userId;
        }
    }

    /** Product names for the stock movements (one lookup for all rows). */
    private Map<String, String> productNames() {
        Map<String, String> names = new HashMap<>();
        Stock stock = stock();
        for (StockSnapshot s : stock.all) {
            names.put(s.productId(), s.name());
        }
        return names;
    }
}