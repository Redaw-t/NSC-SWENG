package system.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One attempt to send a record to another terminal. Unlike the other logs its status changes
 * over time, so this is the one log with an update.
 *
 * @param logId          database ID; 0 until saved
 * @param sourceTerminal the terminal sending
 * @param targetTerminal the terminal it is sent to
 * @param dataType       SALE, PRODUCT or USER
 * @param recordId       the record sent
 * @param status         {@link #PENDING}, {@link #SYNCED} or {@link #FAILED}
 * @param errorMessage   why the last try failed; null if it did not
 */
public record SyncLogEntry(long logId, Instant createdAt, Instant updatedAt, String sourceTerminal,
                           String targetTerminal, String dataType, String recordId, String status,
                           int retryCount, String errorMessage) {

    public static final String PENDING = "PENDING";
    public static final String SYNCED = "SYNCED";
    public static final String FAILED = "FAILED";

    public SyncLogEntry {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(sourceTerminal, "sourceTerminal");
        Objects.requireNonNull(targetTerminal, "targetTerminal");
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(status, "status");
    }

    /** "Pending", "Synced" or "Failed". */
    public String statusLabel() {
        return InventoryLogEntry.label(status);
    }
}
