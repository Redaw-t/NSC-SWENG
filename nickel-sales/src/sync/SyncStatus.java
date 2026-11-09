package sync;

import java.time.Instant;

/**
 * What the status screens show about sync on this PC, kept up to date by {@link SyncEngine}. Read from
 * any thread.
 */
public final class SyncStatus {

    private volatile boolean running;
    private volatile boolean mainPos;
    private volatile Boolean mainReachable;   // null = not tried yet
    private volatile String mainIp;
    private volatile Instant lastContact;
    private volatile Instant offlineSince;
    private volatile String problem;

    /** True once the sync worker runs on this PC. */
    public boolean running() {
        return running;
    }

    /** True if this PC is the Main POS (the hub every other terminal syncs with). */
    public boolean mainPos() {
        return mainPos;
    }

    /** Additional POS: true / false once it has tried to reach the Main POS; null before that. */
    public Boolean mainReachable() {
        return mainReachable;
    }

    /** Additional POS: the Main POS's address it syncs with. */
    public String mainIp() {
        return mainIp;
    }

    /** When this PC last synced without a problem; null if never. */
    public Instant lastContact() {
        return lastContact;
    }

    /** Additional POS: since when the Main POS can't be reached; null while it can. */
    public Instant offlineSince() {
        return offlineSince;
    }

    /** The last problem, in words for an admin; null if the last round went fine. */
    public String problem() {
        return problem;
    }

    void started(boolean isMainPos) {
        running = true;
        mainPos = isMainPos;
    }

    void stopped() {
        running = false;
    }

    void mainIp(String ip) {
        mainIp = ip;
    }

    void ok(Instant at) {
        mainReachable = true;
        lastContact = at;
        offlineSince = null;
        problem = null;
    }

    void unreachable(Instant at, String why) {
        if (offlineSince == null) {
            offlineSince = at;
        }
        mainReachable = false;
        problem = why;
    }

    void problem(String why) {
        problem = why;
    }
}
