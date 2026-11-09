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
     * @param dataType       SEND / RECEIVE for a batch, or what one change is (SALE, PRODUCT, STOCK ...)
     * @param recordId       e.g. "12 changes", or the invoice number / product ID of one change
     * @return the log line's ID, for {@link #succeeded} / {@link #failed}; 0 if it could not be saved
     */
    long attempt(String targetTerminal, String dataType, String recordId);

    /**
     * As {@link #attempt(String, String, String)} for something coming FROM another terminal, e.g. a
     * change made on T02 being applied here.
     */
    default long attempt(String sourceTerminal, String targetTerminal, String dataType, String recordId) {
        return attempt(targetTerminal, dataType, recordId);
    }

    /** The send worked (status SYNCED). */
    void succeeded(long logId);

    /** The send failed (status FAILED, retry count + 1). */
    void failed(long logId, String errorMessage);
}
