package reports.ui;

import app.AppContext;
import app.AppModule;
import app.ComingSoonView;
import app.ScreenContext;
import auth.permission.Permission;
import javafx.scene.Parent;
import reports.service.CashierReportService;
import reports.service.ProductPerformanceService;
import reports.service.SalesReportService;
import reports.service.TransactionHistoryService;

import java.util.ArrayList;
import java.util.List;

/**
 * "Reports" on the home screen and in the sidebar. Opens {@link ReportsScreen} with only the
 * tabs this user may see: the sales tabs need VIEW_SALES_REPORTS (Admin), the inventory tab
 * VIEW_INVENTORY_REPORTS (Admin, Inventory staff). Cashiers do not get the button.
 *
 * <p>The button itself needs VIEW_INVENTORY_REPORTS: a module has one permission, and today
 * every role with sales reports also has inventory reports. ReportsCheck fails if a new role
 * breaks that, so this cannot go wrong silently.
 *
 * <p>The services are registered in app.Main.
 */
public final class ReportsModule implements AppModule {

    public static final String TITLE = "Reports";

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public String description() {
        return "Sales and inventory reports";
    }

    @Override
    public Permission permission() {
        return Permission.VIEW_INVENTORY_REPORTS;
    }

    @Override
    public boolean usesShell() {
        return true;
    }

    @Override
    public Parent open(ScreenContext screen) {
        AppContext app = screen.app();
        List<ReportsScreen.Tab> tabs = new ArrayList<>();

        if (app.authz().checkAccess(screen.user(), Permission.VIEW_SALES_REPORTS)) {
            TransactionHistoryService history = app.find(TransactionHistoryService.class).orElse(null);
            SalesReportService sales = app.find(SalesReportService.class).orElse(null);
            ProductPerformanceService products = app.find(ProductPerformanceService.class).orElse(null);
            CashierReportService cashiers = app.find(CashierReportService.class).orElse(null);
            if (history == null || sales == null || products == null || cashiers == null) {
                return ComingSoonView.notice(TITLE, "The sales reports are not connected in this build.");
            }
            tabs.add(new ReportsScreen.Tab("Transactions", new TransactionsPane(screen, history)));
            tabs.add(new ReportsScreen.Tab("Sales Summary", new SalesSummaryPane(screen, sales)));
            tabs.add(new ReportsScreen.Tab("Top Products", new TopProductsPane(screen, products)));
            tabs.add(new ReportsScreen.Tab("Cashiers & Terminals", new CashierPane(screen, cashiers)));
        }

        if (tabs.isEmpty()) {
            return ComingSoonView.notice(TITLE, "The inventory report is not connected in this build yet.");
        }
        return new ReportsScreen(tabs).getView();
    }
}
