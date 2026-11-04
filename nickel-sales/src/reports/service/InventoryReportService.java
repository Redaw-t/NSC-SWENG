package reports.service;

import auth.permission.Permission;
import inventory.contract.StockOverview;
import inventory.contract.StockSnapshot;
import reports.model.InventoryReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The inventory report: current stock, low-stock and out-of-stock lists, read through
 * Inventory's read-only {@link StockOverview}. Needs VIEW_INVENTORY_REPORTS (Admin, Inventory staff).
 */
public final class InventoryReportService {

    private final StockOverview stock;
    private final ReportsAccess access;

    public InventoryReportService(StockOverview stock, ReportsAccess access) {
        this.stock = Objects.requireNonNull(stock, "stock");
        this.access = Objects.requireNonNull(access, "access");
    }

    public InventoryReport current(String sessionId) throws ReportException {
        access.require(sessionId, Permission.VIEW_INVENTORY_REPORTS);
        List<StockSnapshot> all;
        try {
            all = stock.currentStock();
        } catch (RuntimeException e) {
            throw new ReportException("The stock could not be loaded. Check the database connection and try again.", e);
        }
        List<StockSnapshot> low = new ArrayList<>();
        List<StockSnapshot> out = new ArrayList<>();
        for (StockSnapshot s : all) {
            if (s.level() == StockSnapshot.Level.LOW_STOCK) {
                low.add(s);
            } else if (s.level() == StockSnapshot.Level.OUT_OF_STOCK) {
                out.add(s);
            }
        }
        return new InventoryReport(all, low, out);
    }
}
