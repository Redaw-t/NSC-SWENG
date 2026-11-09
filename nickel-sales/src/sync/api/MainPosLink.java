package sync.api;

import java.util.List;

/**
 * What an Additional POS's sync needs from the Main POS: send its changes, get everyone's. The real
 * one is {@link MainPosClient} (over the network); the self-test plugs the Main POS's engine in
 * directly.
 */
public interface MainPosLink {

    /** Sends queued changes, oldest first; returns how many the Main POS took. */
    int push(String terminalId, List<SyncHub.Sent> changes) throws MainPosClient.MainPosException;

    /** The Main POS's list of changes after place {@code after}, at most {@code limit}. */
    SyncHub.Page pull(long after, int limit) throws MainPosClient.MainPosException;

    /** As {@link #pull(long, int)}, letting the Main POS hold the call up to {@code waitMillis} until something new arrives. */
    default SyncHub.Page pull(long after, int limit, long waitMillis) throws MainPosClient.MainPosException {
        return pull(after, limit);
    }

    /**
     * Returns if the Main POS answers right now (quickly); throws if not.
     *
     * @return the Main POS's computer name, or null if it didn't say
     */
    String ping() throws MainPosClient.MainPosException;

    /** The Main POS's address (shown in the status). */
    String mainIp();
}
