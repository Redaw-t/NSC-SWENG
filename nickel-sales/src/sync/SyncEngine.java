package sync;

import shared.SyncTables;
import sync.api.MainPosClient;
import sync.api.MainPosLink;
import sync.api.SyncHub;
import system.contract.SyncLogWriter;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps this PC's store database in step with every other POS, one round at a time ({@link #cycle},
 * run every few seconds by {@link SyncWorker}). The Main POS is the hub:
 *
 * <pre>
 *  every PC:      action ──same transaction──► store tables + SyncOutbox (its queue)
 *  Additional:    SyncOutbox ──push──► Main POS's SyncFeed      (Main POS: its own queue moves there too)
 *  Main POS:      SyncFeed numbers every change: ONE order for the whole store
 *  every PC:      SyncFeed after its place ──pull──► applied in order (ChangeApplier) + place moved, together
 * </pre>
 *
 * A PC applies every change in the list, its own included (they are skipped or rewritten unchanged),
 * so all PCs end up with the same data. While the Main POS can't be reached an Additional POS keeps
 * selling and its queue grows; it is sent, in order, when the Main POS is back. A change sent twice is
 * taken once. A change this PC can't apply is logged in the Sync log (Failed, with a Retry) and
 * skipped; anything else that goes wrong stops the round, and the next round tries again.
 *
 * On the Main POS it is also the {@link SyncHub} behind the API's sync calls.
 */
public final class SyncEngine implements SyncHub {

    /** Changes per batch sent, fetched or moved. */
    static final int BATCH = 200;
    /** Sent queue rows are kept this long, then deleted. */
    static final Duration KEEP_SENT = Duration.ofDays(30);
    /** The Main POS keeps its list this long. */
    static final Duration KEEP_FEED = Duration.ofDays(90);
    /** An Additional POS looks for the Main POS on the network after this long without reaching it. */
    static final Duration FIND_AFTER = Duration.ofSeconds(30);
    /** ... and then at most this often. */
    static final Duration FIND_EVERY = Duration.ofSeconds(60);

    /** Looks for the Main POS on the network (sync.discovery.MainPosFinder); empty if none answered. */
    @FunctionalInterface
    public interface Finder {
        Optional<String> find();
    }

    private final SyncDb db;
    private final ChangeApplier applier = new ChangeApplier();
    private final String terminalId;
    private final boolean mainPos;
    private final Supplier<? extends MainPosLink> client;         // Additional POS: talks to the Main POS; null on the Main POS
    private final Consumer<String> saveMainIp;            // Additional POS: the Main POS was found at a new address
    private final Finder finder;                          // may be null: no looking on the network
    private final BiConsumer<String, String> addressSeen; // Main POS: (terminal, IP) of a terminal that sent changes
    private final SyncLogWriter log;                      // may be null
    private final Clock clock;
    private final SyncStatus status = new SyncStatus();
    private Instant lastPurge = Instant.EPOCH;
    private Instant lastFind = Instant.EPOCH;

    private SyncEngine(SyncDb db, String terminalId, boolean mainPos, Supplier<? extends MainPosLink> client,
                       Consumer<String> saveMainIp, Finder finder, BiConsumer<String, String> addressSeen,
                       SyncLogWriter log, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.mainPos = mainPos;
        this.client = client;
        this.saveMainIp = saveMainIp;
        this.finder = finder;
        this.addressSeen = addressSeen;
        this.log = log;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The Main POS's engine.
     *
     * @param addressSeen told (terminal, IP) whenever a terminal sends changes, so the terminal list
     *                    keeps up when the router gives a terminal a new address; may be null
     */
    public static SyncEngine mainPos(SyncDb db, String terminalId, BiConsumer<String, String> addressSeen,
                                     SyncLogWriter log, Clock clock) {
        return new SyncEngine(db, terminalId, true, null, null, null, addressSeen, log, clock);
    }

    /**
     * An Additional POS's engine.
     *
     * @param client     a client for the Main POS at its saved address (asked every round), or null
     *                   if it is not known
     * @param saveMainIp saves a new address for the Main POS, found on the network
     * @param finder     looks for the Main POS on the network; null = never
     */
    public static SyncEngine additional(SyncDb db, String terminalId, Supplier<? extends MainPosLink> client,
                                        Consumer<String> saveMainIp, Finder finder, SyncLogWriter log, Clock clock) {
        return new SyncEngine(db, terminalId, false, Objects.requireNonNull(client, "client"), saveMainIp, finder,
                null, log, clock);
    }

    public SyncStatus status() {
        return status;
    }

    public boolean isMainPos() {
        return mainPos;
    }

    void started() {
        status.started(mainPos);
    }

    void stopped() {
        status.stopped();
    }

    /* ----------------------------------- one round ----------------------------------- */

    /** One round of syncing. Never throws: problems are recorded in the status and the Sync log. */
    public synchronized void cycle() {
        try {
            if (mainPos) {
                mainRound();
            } else {
                additionalRound();
            }
            housekeeping();
        } catch (SQLException | RuntimeException e) {
            String why = "This PC's database: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            System.err.println("[SYNC] " + why);
            status.problem(why);
        }
    }

    private void mainRound() throws SQLException {
        Instant now = clock.instant();
        while (db.drainOwnOutbox(terminalId, BATCH, now).size() == BATCH) {
            // more of this PC's own changes waiting: keep moving them into the list
        }
        long seq = db.appliedSeq();
        while (true) {
            List<SyncDb.FeedEntry> entries = db.feedAfter(seq, BATCH);
            for (SyncDb.FeedEntry e : entries) {
                applyEntry(e);
                seq = e.seq();
            }
            if (entries.size() < BATCH) {
                break;
            }
        }
        status.ok(clock.instant());
    }

    private void additionalRound() throws SQLException {
        MainPosLink main = client.get();
        if (main == null) {
            status.problem("The Main POS's address is not known. Set it in Settings.");
            return;
        }
        status.mainIp(main.mainIp());
        try {
            push(main);
            pull(main);
            if (Boolean.FALSE.equals(status.mainReachable())) {
                logLine("T01", "LINK", "Main POS reachable again", null);
            }
            status.ok(clock.instant());
        } catch (MainPosClient.MainPosException e) {
            if (!Boolean.FALSE.equals(status.mainReachable())) {
                // once per outage, not every few seconds
                logLine("T01", "LINK", "Main POS not reachable; changes are kept here", e.getMessage());
            }
            status.unreachable(clock.instant(), e.getMessage());
            lookForMain(main.mainIp());
        }
    }

    private void push(MainPosLink main) throws SQLException, MainPosClient.MainPosException {
        while (true) {
            List<SyncDb.Change> unsent = db.unsent(BATCH);
            if (unsent.isEmpty()) {
                return;
            }
            List<SyncHub.Sent> batch = new ArrayList<>();
            for (SyncDb.Change c : unsent) {
                batch.add(new SyncHub.Sent(c.changeId(), c.kind(), c.recordId(),
                        c.createdAt() == null ? null : c.createdAt().toString(), c.payload()));
            }
            main.push(terminalId, batch);
            db.markSent(unsent, clock.instant());
            logLine("T01", "SEND", unsent.size() + (unsent.size() == 1 ? " change" : " changes"), null);
            if (unsent.size() < BATCH) {
                return;
            }
        }
    }

    private void pull(MainPosLink main) throws SQLException, MainPosClient.MainPosException {
        long seq = db.appliedSeq();
        while (true) {
            SyncHub.Page page = main.pull(seq, BATCH);
            for (SyncHub.Listed c : page.changes()) {
                applyEntry(new SyncDb.FeedEntry(c.seq(), c.changeId(), c.source(), c.kind(), c.recordId(),
                        c.payload(), parse(c.createdAt())));
                seq = c.seq();
            }
            if (!page.changes().isEmpty()) {
                int n = page.changes().size();
                logLineFrom("T01", terminalId, "RECEIVE", n + (n == 1 ? " change" : " changes"), null);
            }
            if (page.changes().size() < BATCH) {
                return;
            }
        }
    }

    /** Applies one change and moves this PC's place past it, together; a change this PC can't take is logged and skipped. */
    private void applyEntry(SyncDb.FeedEntry e) throws SQLException {
        applier.warnings();   // start clean
        try {
            db.inTransaction(tx -> {
                try {
                    applier.apply(tx, e.payload());
                } catch (ChangeApplier.RejectedException rejected) {
                    throw new Rejected(rejected);   // rolls the change back
                }
                db.setAppliedSeq(tx, e.seq());
                return null;
            });
        } catch (Rejected r) {
            String why = r.getCause().getMessage();
            System.err.println("[SYNC] change " + e.seq() + " (" + e.kind() + " " + e.recordId() + " from "
                    + e.source() + ") was not applied: " + why);
            long logId = logLineFrom(e.source(), terminalId, e.kind(), e.recordId(), why);
            db.saveFailed(logId, e, clock.instant());
            db.inTransaction(tx -> {
                db.setAppliedSeq(tx, e.seq());
                return null;
            });
            return;
        }
        for (String warning : applier.warnings()) {
            System.err.println("[SYNC] " + warning);
            logLineFrom(e.source(), terminalId, SyncTables.STOCK, e.recordId(), warning);
        }
    }

    /** Wraps a refused change so the transaction rolls back. */
    private static final class Rejected extends RuntimeException {
        Rejected(ChangeApplier.RejectedException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /**
     * Sync log Retry of a change that could not be applied: tries it again (the place in the list is
     * not moved back). For anything else, the next round simply runs.
     *
     * @return true if a failed change was retried and applied
     * @throws Exception it still can't be applied (the message says why)
     */
    public synchronized boolean retry(long syncLogId) throws Exception {
        Optional<SyncDb.FeedEntry> failed = db.failed(syncLogId);
        if (failed.isEmpty()) {
            return false;
        }
        try {
            db.inTransaction(tx -> {
                try {
                    applier.apply(tx, failed.get().payload());
                } catch (ChangeApplier.RejectedException rejected) {
                    throw new Rejected(rejected);
                }
                return null;
            });
        } catch (Rejected r) {
            throw new Exception("It still can't be applied: " + r.getCause().getMessage());
        }
        db.deleteFailed(syncLogId);
        if (log != null) {
            log.succeeded(syncLogId);
        }
        return true;
    }

    /** Additional POS, Main POS unreachable for a while: look for it on the network, and follow it if it moved. */
    private void lookForMain(String currentIp) {
        Instant now = clock.instant();
        Instant since = status.offlineSince();
        if (finder == null || saveMainIp == null || since == null
                || Duration.between(since, now).compareTo(FIND_AFTER) < 0
                || Duration.between(lastFind, now).compareTo(FIND_EVERY) < 0) {
            return;
        }
        lastFind = now;
        Optional<String> found = finder.find();
        if (found.isPresent() && !found.get().equals(currentIp)) {
            saveMainIp.accept(found.get());
            System.out.println("[SYNC] The Main POS moved from " + currentIp + " to " + found.get());
            logLine("T01", "LINK", "Main POS found at " + found.get() + " (was " + currentIp + ")", null);
        }
    }

    private void housekeeping() throws SQLException {
        Instant now = clock.instant();
        if (Duration.between(lastPurge, now).toHours() >= 1) {
            lastPurge = now;
            db.purge(now.minus(KEEP_SENT), now.minus(KEEP_FEED));
        }
    }

    /* ------------------------------- Main POS: SyncHub ------------------------------- */

    @Override
    public int accept(String source, String remoteIp, List<SyncHub.Sent> changes) throws SQLException {
        List<SyncDb.Change> list = new ArrayList<>();
        for (SyncHub.Sent s : changes) {
            list.add(new SyncDb.Change(s.changeId(), s.kind() == null ? "" : s.kind(),
                    s.recordId() == null ? "" : s.recordId(), s.payload(), Optional.ofNullable(parse(s.createdAt()))
                    .orElse(clock.instant())));
        }
        int taken = db.accept(source, list, clock.instant());
        if (addressSeen != null && remoteIp != null) {
            try {
                addressSeen.accept(source, remoteIp);
            } catch (RuntimeException e) {
                System.err.println("[SYNC] could not update " + source + "'s address: " + e.getMessage());
            }
        }
        return taken;
    }

    @Override
    public SyncHub.Page pull(long after, int limit) throws SQLException {
        long head = db.feedHead();
        List<SyncHub.Listed> out = new ArrayList<>();
        if (limit > 0) {
            for (SyncDb.FeedEntry e : db.feedAfter(after, limit)) {
                out.add(new SyncHub.Listed(e.seq(), e.changeId(), e.source(), e.kind(), e.recordId(),
                        e.createdAt() == null ? null : e.createdAt().toString(), e.payload()));
            }
        }
        return new SyncHub.Page(head, out);
    }

    @Override
    public String nextId(String kind) throws SQLException {
        return db.allocateId(kind);
    }

    @Override
    public long appliedSeq() throws SQLException {
        return db.appliedSeq();
    }

    /* ----------------------------------- helpers ----------------------------------- */

    private long logLine(String target, String type, String record, String error) {
        return logLineFrom(terminalId, target, type, record, error);
    }

    /** One Sync log line: Synced, or Failed with {@code error}. Returns its ID (0 if not logged). */
    private long logLineFrom(String source, String target, String type, String record, String error) {
        if (log == null) {
            return 0;
        }
        long id = log.attempt(source, target, type, record);
        if (error == null) {
            log.succeeded(id);
        } else {
            log.failed(id, error);
        }
        return id;
    }

    private static Instant parse(String iso) {
        try {
            return iso == null || iso.isBlank() ? null : Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
