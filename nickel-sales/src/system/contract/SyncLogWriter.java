package system.contract;

/**
 * CONTRACT for the Sync module: how it records its attempts in the Sync Logs tab.
 * Get it with {@code app.find(SyncLogWriter.class)}. Writing a log line never throws: a logging
 * problem must not stop a sync.
 */
public interface SyncLogWriter {

    /**
     * Records that a send is starting (status PENDING).
     *
     * @param targetTerminal the terminal it is sent to, e.g. T02
     * @param dataType       SALE, PRODUCT or USER
     * @param recordId       the sale, product or user being sent
     * @return the log line's ID, for {@link #succeeded} / {@link #failed}; 0 if it could not be saved
     */
    long attempt(String targetTerminal, String dataType, String recordId);

    /** The send worked (status SYNCED). */
    void succeeded(long logId);

    /** The send failed (status FAILED, retry count + 1). */
    void failed(long logId, String errorMessage);
}
