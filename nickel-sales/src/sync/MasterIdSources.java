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

    /** An Additional POS that synced this recently counts as reaching the Main POS without asking again. */
    static final Duration RECENT = Duration.ofSeconds(15);

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

            @Override
            public void requireMain() {
                Instant last = status.lastContact();
                if (Boolean.TRUE.equals(status.mainReachable()) && last != null
                        && Duration.between(last, clock.instant()).compareTo(RECENT) <= 0) {
                    return;
                }
                MainPosClient main = client.get();
                if (main == null) {
                    throw new MainPosUnavailableException();
                }
                try {
                    main.pull(Long.MAX_VALUE - 1, 0);   // a quick call that needs the key
                } catch (MainPosClient.MainPosException e) {
                    throw new MainPosUnavailableException(e);
                }
            }
        };
    }
}
