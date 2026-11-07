package system.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import shared.Money;
import shared.PhTime;
import shared.Quantity;
import system.model.SaleLineLogEntry;
import system.model.SaleLineLogFilter;
import system.repository.SaleLineLogRepository;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads and exports the open item and edited-price history for the "Open Items &amp; Price Edits"
 * tab (Admin only). Never changes it.
 */
public final class SaleLineLogService {

    /** The most rows one search returns; narrow the dates to see older ones. */
    public static final int MAX_ROWS = 5000;

    private final SaleLineLogRepository repository;
    private final SystemAccess access;
    private final AuditLogger audit;

    public SaleLineLogService(SaleLineLogRepository repository, SystemAccess access, AuditLogger audit) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = audit;   // may be null: then exports are not logged
    }

    /** Lines matching {@code filter}, newest sale first, at most {@link #MAX_ROWS}. */
    public List<SaleLineLogEntry> find(String sessionId, SaleLineLogFilter filter) throws SystemException {
        access.require(sessionId);
        try {
            return repository.find(filter == null ? SaleLineLogFilter.all() : filter, MAX_ROWS);
        } catch (RuntimeException e) {
            throw new SystemException("The open item and price edit history could not be loaded. Try again.", e);
        }
    }

    /**
     * Writes {@code rows} (what the screen is showing) to a CSV file that opens in Excel.
     *
     * @param userName the person's name for a user ID, or null if unknown
     * @return how many rows were written
     */
    public int exportCsv(String sessionId, List<SaleLineLogEntry> rows, Path file,
                         Function<String, String> userName) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(file, "file");
        List<List<String>> lines = new ArrayList<>();
        for (SaleLineLogEntry e : rows) {
            String name = userName == null ? null : userName.apply(e.cashierUserId());
            lines.add(Arrays.asList(ActivityLogService.CSV_TIME.format(PhTime.of(e.completedAt())), e.invoiceNo(),
                    e.typeLabel(), e.productId(), e.productName(), Quantity.format(e.quantity()), e.unit(),
                    money(e.originalPrice()), money(e.unitPrice()), money(e.lineTotal()),
                    e.vatable() ? "VATable" : "VAT-exempt", e.cashierUserId(), name));
        }
        Csv.write(file, List.of("Date / Time", "Invoice No.", "Type", "Product ID", "Item", "Quantity", "Unit",
                "Original Price", "Price Charged", "Amount", "VAT", "Cashier ID", "Cashier"), lines);
        if (audit != null) {
            audit.log("LOGS_EXPORTED target=OpenItemsPriceEdits " + rows.size() + " rows", actor.getUserId());
        }
        return rows.size();
    }

    private static String money(BigDecimal value) {
        return value == null ? null : Money.format(value);
    }
}
