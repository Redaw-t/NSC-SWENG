package sync;

import shared.MainPosUnavailableException;
import shared.MasterIds;
import sync.api.MainPosClient;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Where new product / user / category / supplier IDs come from ({@link MasterIds}) once the terminals
 * sync: the Main POS hands them out, to itself and to every Additional POS, so two terminals never
 * make the same one. An Additional POS may add or change these shared records only while it can reach
 * the Main POS.
 */
public final class MasterIdSources {

    /** Reached this recently (the check runs every second) counts as reaching the Main POS now. */
    static final Duration RECENT = Duration.ofSeconds(3);

    private MasterIdSources() { }

    /** On the Main POS: it hands out the IDs itself; changes are always allowed. */
    public static MasterIds.Source mainPos(SyncDb db) {
        return new MasterIds.Source() {
            @Override
            public String next(String kind) {
                try {
                    return db.allocateId(kind);
                } catch (SQLException e) {
                    throw new RuntimeException("Could not make a new ID", e);
                }
            }

            @Override
            public void requireMain() {
                // this IS the Main POS
            }
        };
    }

    /**
     * On an Additional POS: asks the Main POS.
     *
     * @param client a client for the Main POS at its saved address, or null if none is known
     * @param status this PC's sync status (a recent sync counts as reaching the Main POS)
     */
    public static MasterIds.Source additional(Supplier<MainPosClient> client, SyncStatus status, Clock clock) {
        return new MasterIds.Source() {
            @Override
            public String next(String kind) {
                requireMain();   // known to be off: say so at once, without waiting on the network
                MainPosClient main = client.get();
                if (main == null) {
                    throw new MainPosUnavailableException();
                }
                try {
                    return main.nextId(kind);
                } catch (MainPosClient.MainPosException e) {
                    throw new MainPosUnavailableException(e);
                }
            }

            /**
             * Answered from the every-second check (sync.SyncWorker), so it never makes the person wait:
             * reached within the last few seconds = fine; known to be off = refused at once. Only before
             * the first check (just after start-up) does it ask the Main POS itself.
             */
            @Override
            public void requireMain() {
                Boolean reachable = status.mainReachable();
                Instant last = status.lastContact();
                if (Boolean.TRUE.equals(reachable) && last != null
                        && Duration.between(last, clock.instant()).compareTo(RECENT) <= 0) {
                    return;
                }
                if (Boolean.FALSE.equals(reachable)) {
                    throw new MainPosUnavailableException();
                }
                MainPosClient main = client.get();
                if (main == null) {
                    throw new MainPosUnavailableException();
                }
                try {
                    main.ping();
                } catch (MainPosClient.MainPosException e) {
                    throw new MainPosUnavailableException(e);
                }
            }
        };
    }
}
