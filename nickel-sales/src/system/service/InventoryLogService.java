package system.service;

import auth.contract.AuditLogger;
import auth.contract.User;
import shared.PhTime;
import shared.Quantity;
import system.model.InventoryLogEntry;
import system.model.InventoryLogFilter;
import system.repository.InventoryLogRepository;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Reads and exports the stock movement history for the Inventory Logs tab (Admin only). Never changes it. */
public final class InventoryLogService {

    /** The most rows one search returns; narrow the dates to see older ones. */
    public static final int MAX_ROWS = 5000;

    private final InventoryLogRepository repository;
    private final SystemAccess access;
    private final AuditLogger audit;

    public InventoryLogService(InventoryLogRepository repository, SystemAccess access, AuditLogger audit) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = audit;   // may be null: then exports are not logged
    }

    /** Movements matching {@code filter}, newest first, at most {@link #MAX_ROWS}. */
    public List<InventoryLogEntry> find(String sessionId, InventoryLogFilter filter) throws SystemException {
        access.require(sessionId);
        try {
            return repository.find(filter == null ? InventoryLogFilter.all() : filter, MAX_ROWS);
        } catch (RuntimeException e) {
            throw new SystemException("The inventory log could not be loaded. Try again.", e);
        }
    }

    /**
     * Writes {@code rows} (what the screen is showing) to a CSV file that opens in Excel.
     *
     * @param userName the person's name for a user ID, or null if unknown
     * @return how many rows were written
     */
    public int exportCsv(String sessionId, List<InventoryLogEntry> rows, Path file,
                         Function<String, String> userName) throws SystemException {
        User actor = access.require(sessionId);
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(file, "file");
        List<List<String>> lines = new ArrayList<>();
        for (InventoryLogEntry e : rows) {
            String name = e.performedBy() == null || userName == null ? null : userName.apply(e.performedBy());
            lines.add(Arrays.asList(ActivityLogService.CSV_TIME.format(PhTime.of(e.createdAt())), e.productId(),
                    e.productName(), InventoryLogEntry.label(e.type()), qty(e.quantity()), qty(e.quantityBefore()),
                    qty(e.quantityAfter()), InventoryLogEntry.label(e.reason()), e.referenceNo(), e.note(),
                    e.performedBy(), name));
        }
        Csv.write(file, List.of("Date / Time", "Product ID", "Product", "Type", "Change", "Before", "After",
                "Reason", "Reference No.", "Note", "User ID", "User"), lines);
        if (audit != null) {
            audit.log("LOGS_EXPORTED target=InventoryLog " + rows.size() + " rows", actor.getUserId());
        }
        return rows.size();
    }

    private static String qty(BigDecimal value) {
        return value == null ? null : Quantity.format(value);
    }
}
