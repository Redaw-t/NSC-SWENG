package system.model;

import java.time.LocalDate;

/**
 * What the Sync Logs tab asks for. Every part is optional (null = no limit on it).
 *
 * @param from    first day, in Philippine time
 * @param to      last day (included), in Philippine time
 * @param status  PENDING / SYNCED / FAILED
 * @param keyword found in the record ID, terminal or error message
 */
public record SyncLogFilter(LocalDate from, LocalDate to, String status, String keyword) {

    public static SyncLogFilter all() {
        return new SyncLogFilter(null, null, null, null);
    }

    public SyncLogFilter {
        status = status == null || status.isBlank() ? null : status.trim();
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The From date is after the To date.");
        }
    }
}
