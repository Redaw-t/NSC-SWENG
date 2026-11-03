package system.contract;

/**
 * CONTRACT for the Sync module: what the Retry button of the Sync Logs tab calls. The tab only
 * forwards the request; the retry logic stays in Sync. Register one with
 * {@code app.find(SyncLogService.class).get().setRetryHandler(...)}. Until then Retry says Sync is
 * not connected.
 */
@FunctionalInterface
public interface SyncRetryHandler {

    /**
     * Queues the record to be sent again.
     *
     * @throws Exception the request could not be queued; the message is shown to the admin
     */
    void retry(long logId, String dataType, String recordId) throws Exception;
}
