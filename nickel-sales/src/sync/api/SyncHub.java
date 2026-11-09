package sync.api;

import java.util.List;

/**
 * CONTRACT, implemented on the Main POS (sync.SyncEngine): what the API's sync calls do.
 *
 * | Method | Path                    | Does                                                         |
 * | POST   | /api/sync/push          | an Additional POS sends its queued changes; reply how many   |
 * | GET    | /api/sync/pull?after=N  | the Main POS's list of changes after place N (all terminals') |
 * | GET    | /api/sync/next-id?kind= | a new product / user / category / supplier ID                 |
 */
public interface SyncHub {

    /** A change as an Additional POS sends it. {@code payload} is JSON text. */
    record Sent(String changeId, String kind, String recordId, String createdAt, String payload) { }

    /** A change in the Main POS's list. {@code payload} is JSON text. */
    record Listed(long seq, String changeId, String source, String kind, String recordId, String createdAt,
                  String payload) { }

    /** Part of the list, and the list's last place. */
    record Page(long head, List<Listed> changes) { }

    /**
     * Takes changes from terminal {@code source} (at {@code remoteIp}); a change already taken is
     * skipped.
     *
     * @return how many were taken
     */
    int accept(String source, String remoteIp, List<Sent> changes) throws Exception;

    /** The list after place {@code after}, at most {@code limit} changes. */
    Page pull(long after, int limit) throws Exception;

    /**
     * As {@link #pull(long, int)}, but if nothing is new yet, waits up to {@code waitMillis} for
     * something to arrive and answers as soon as it does ("wait for news", so a change on one terminal
     * shows on the others at once).
     */
    default Page pull(long after, int limit, long waitMillis) throws Exception {
        return pull(after, limit);
    }

    /** A new ID of {@code kind} (shared.MasterIds), never handed out twice. */
    String nextId(String kind) throws Exception;

    /**
     * The last place in the list already applied to the Main POS's own database. A new Additional
     * POS's copy of the database starts there (sent with the copy as X-Sync-Seq).
     */
    long appliedSeq() throws Exception;
}
