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
import java.util.function.Supplier;

/**
 * Keeps this PC's store database in step with every other POS. The Main POS is the hub:
 *
 * <pre>
 *  every PC:      action ──same transaction──► store tables + SyncOutbox (its queue)
 *  Additional:    SyncOutbox ──push──► Main POS's SyncFeed      (Main POS: its own queue moves there too)
 *  Main POS:      SyncFeed numbers every change: ONE order for the whole store
 *  every PC:      SyncFeed after its place ──pull──► applied in order (ChangeApplier) + place moved, together
 * </pre>
 *
 * Instant, not every few seconds ({@link SyncWorker} runs the parts):
 * - a change made here is sent straight away ({@link #pushRound}, woken by shared.SyncOutbox);
 * - an Additional POS keeps a "wait for news" call open to the Main POS ({@link #pullRound} with a
 *   wait), which the Main POS answers the moment a change arrives from any terminal;
 * - an Additional POS checks every second that the Main POS answers ({@link #heartbeat}), so System
 *   Status and "this needs the Main POS" know at once when it is off.
 *
 * A PC applies every change in the list, its own included (they are skipped or rewritten unchanged),
 * so all PCs end up with the same data. While the Main POS can't be reached an Additional POS keeps
 * selling and its queue grows; it is sent, in order, when the Main POS is back. A change sent twice is
 * taken once. A change this PC can't apply is logged in the Sync log (Failed, with a Retry) and
 * skipped; anything else that goes wrong stops that part, and it tries again a moment later.
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
    /** The longest a "wait for news" call is held open before the Main POS answers "nothing new". */
    public static final long MAX_WAIT_MILLIS = 20_000;
    /** An Additional POS looks for the Main POS on the network after this long without reaching it ... */
    static final Duration FIND_AFTER = Duration.ofSeconds(30);
    /** ... and then at most this often. */
    static final Duration FIND_EVERY = Duration.ofSeconds(60);

    /** Looks for this store's Main POS on the network (sync.discovery.MainPosScanner); empty if not found. */
    @FunctionalInterface
    public interface Finder {
        Optional<String> find();
    }

    private final SyncDb db;
    private final ChangeApplier applier = new ChangeApplier();
    private final String terminalId;
    private final boolean mainPos;
    private final Supplier<? extends MainPosLink> client;         // Additional POS: talks to the Main POS; null on the Main POS
    private final BiConsumer<String, String> addressSeen; // Main POS: (terminal, IP) of a terminal that sent changes
    private final Finder finder;                          // Additional POS: may be null (never looks)
    private final java.util.function.Consumer<String> saveMainIp;   // Additional POS: the Main POS was found at a new address
    private final SyncLogWriter log;                      // may be null
    private final Clock clock;
    private final SyncStatus status = new SyncStatus();

    private final Object pushLock = new Object();
    private final Object pullLock = new Object();         // applying changes (and Retry)
    private final Object mainLock = new Object();
    private final Object feedSignal = new Object();       // Main POS: notified when its list grows
    private long feedVersion;
    private volatile Runnable wakeMain = () -> { };      // Main POS: run a round now (set by SyncWorker)
    private Instant lastPurge = Instant.EPOCH;
    private Instant lastFind = Instant.EPOCH;
    private volatile boolean foreignApplied;   // a change made on another register was applied since the last announce
    private volatile java.util.function.Consumer<String> saveMainName;   // Additional POS: may be null
    private String lastMainName;

    private SyncEngine(SyncDb db, String terminalId, boolean mainPos, Supplier<? extends MainPosLink> client,
                       BiConsumer<String, String> addressSeen, Finder finder,
                       java.util.function.Consumer<String> saveMainIp, SyncLogWriter log, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.mainPos = mainPos;
        this.client = client;
        this.addressSeen = addressSeen;
        this.finder = finder;
        this.saveMainIp = saveMainIp;
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
        return new SyncEngine(db, terminalId, true, null, addressSeen, null, null, log, clock);
    }

    /**
     * An Additional POS's engine.
     *
     * @param client the Main POS at its saved address (asked each time), or null if it is not known
     */
    public static SyncEngine additional(SyncDb db, String terminalId, Supplier<? extends MainPosLink> client,
                                        SyncLogWriter log, Clock clock) {
        return additional(db, terminalId, client, null, null, log, clock);
    }

    /**
     * As above; when the Main POS can't be reached for a while, {@code finder} looks for it on the
     * network, and if it answers at a new address, {@code saveMainIp} saves that address.
     */
    public static SyncEngine additional(SyncDb db, String terminalId, Supplier<? extends MainPosLink> client,
                                        Finder finder, java.util.function.Consumer<String> saveMainIp,
                                        SyncLogWriter log, Clock clock) {
        return new SyncEngine(db, terminalId, false, Objects.requireNonNull(client, "client"), null, finder,
                saveMainIp, log, clock);
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

    /**
     * Additional POS: told the Main POS's computer name when it is first seen and whenever it changes
     * (not every second), so this PC's terminal list names the Main POS too.
     */
    public void onMainName(java.util.function.Consumer<String> save) {
        saveMainName = save;
        lastMainName = null;
    }

    private void mainNameSeen(String name) {
        java.util.function.Consumer<String> save = saveMainName;
        if (save == null || name == null || name.equals(lastMainName)) {
            return;
        }
        try {
            save.accept(name);
            lastMainName = name;
        } catch (RuntimeException e) {
            System.err.println("[SYNC] could not save the Main POS's computer name: " + e.getMessage());
        }
    }

    void onFeedGrew(Runnable wake) {
        wakeMain = wake == null ? () -> { } : wake;
    }

    /* ------------------------------------ rounds ------------------------------------ */

    /**
     * One whole round, all parts once, without waiting (the self-test drives sync with it).
     * Never throws.
     */
    public void cycle() {
        if (mainPos) {
            mainRound();
        } else {
            pushRound();
            pullRound(0);
        }
    }

    /** Main POS: moves its own queued changes into the list and applies the list. Never throws. */
    public void mainRound() {
        synchronized (mainLock) {
            try {
                Instant now = clock.instant();
                int moved;
                boolean any = false;
                while ((moved = db.drainOwnOutbox(terminalId, BATCH, now).size()) > 0) {
                    any = true;
                    if (moved < BATCH) {
                        break;
                    }
                }
                if (any) {
                    feedGrew();   // terminals waiting for news get it now
                }
                synchronized (pullLock) {
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
                }
                status.ok(clock.instant());
                announceNewData();
                housekeeping();
            } catch (SQLException | RuntimeException e) {
                databaseProblem(e);
            }
        }
    }

    /** Additional POS: sends what is queued here, oldest first. Never throws. */
    public void pushRound() {
        synchronized (pushLock) {
            MainPosLink main = main();
            if (main == null) {
                return;
            }
            boolean contacted = false;
            try {
                while (true) {
                    List<SyncDb.Change> unsent = db.unsent(BATCH);
                    if (unsent.isEmpty()) {
                        break;
                    }
                    List<SyncHub.Sent> batch = new ArrayList<>();
                    for (SyncDb.Change c : unsent) {
                        batch.add(new SyncHub.Sent(c.changeId(), c.kind(), c.recordId(),
                                c.createdAt() == null ? null : c.createdAt().toString(), c.payload()));
                    }
                    main.push(terminalId, batch);
                    contacted = true;
                    db.markSent(unsent, clock.instant());
                    logLine("T01", "SEND", unsent.size() + (unsent.size() == 1 ? " change" : " changes"), null);
                    if (unsent.size() < BATCH) {
                        break;
                    }
                }
                if (contacted) {
                    // Only a call that really reached the Main POS says it is on. With nothing to send,
                    // nothing was asked: the every-second check decides (else the status would flip
                    // between OK and offline while the Main POS is off).
                    reachable();
                }
            } catch (MainPosClient.MainPosException e) {
                unreachable(e.getMessage());
            } catch (SQLException | RuntimeException e) {
                databaseProblem(e);
            }
        }
    }

    /**
     * Additional POS: gets the changes after this PC's place and applies them. With {@code waitMillis},
     * the Main POS holds the call until something new arrives (or the wait ends), so a change made on
     * any terminal shows here at once. Never throws.
     */
    public void pullRound(long waitMillis) {
        synchronized (pullLock) {
            MainPosLink main = main();
            if (main == null) {
                return;
            }
            try {
                long seq = db.appliedSeq();
                long wait = waitMillis;
                while (true) {
                    SyncHub.Page page = main.pull(seq, BATCH, wait);
                    wait = 0;   // only the first call waits
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
                        break;
                    }
                }
                reachable();
                announceNewData();
                housekeeping();
            } catch (MainPosClient.MainPosException e) {
                unreachable(e.getMessage());
            } catch (SQLException | RuntimeException e) {
                databaseProblem(e);
            }
        }
    }

    /** Additional POS: is the Main POS answering? (every second). Never throws. */
    public void heartbeat() {
        MainPosLink main = main();
        if (main == null) {
            return;
        }
        try {
            String name = main.ping();
            reachable();
            mainNameSeen(name);
        } catch (MainPosClient.MainPosException e) {
            unreachable(e.getMessage());
            lookForMain(main.mainIp());
        }
    }

    /**
     * Main POS unreachable for a while: look for it on the network (the router may have given it a
     * new address) and follow it if found. At most once a minute.
     */
    private void lookForMain(String currentIp) {
        Instant now = clock.instant();
        Instant since = status.offlineSince();
        if (finder == null || saveMainIp == null || since == null
                || Duration.between(since, now).compareTo(FIND_AFTER) < 0
                || Duration.between(lastFind, now).compareTo(FIND_EVERY) < 0) {
            return;
        }
        lastFind = now;
        Optional<String> found;
        try {
            found = finder.find();
        } catch (RuntimeException e) {
            System.err.println("[SYNC] looking for the Main POS failed: " + e.getMessage());
            return;
        }
        if (found.isPresent() && !found.get().equals(currentIp)) {
            saveMainIp.accept(found.get());
            System.out.println("[SYNC] The Main POS moved from " + currentIp + " to " + found.get());
            logLine("T01", "LINK", "Main POS found at " + found.get() + " (was " + currentIp + ")", null);
        }
    }

    private MainPosLink main() {
        MainPosLink main;
        try {
            main = client.get();
        } catch (RuntimeException e) {
            status.problem("This PC's terminal settings can't be read: " + e.getMessage());
            return null;
        }
        if (main == null) {
            status.problem("The Main POS's address is not known. Set it in Settings.");
            return null;
        }
        status.mainIp(main.mainIp());
        return main;
    }

    private void reachable() {
        if (status.ok(clock.instant())) {
            logLine("T01", "LINK", "Main POS reachable again", null);
        }
    }

    private void unreachable(String why) {
        if (status.unreachable(clock.instant(), why)) {
            // once per outage, not every second
            logLine("T01", "LINK", "Main POS not reachable; changes are kept here", why);
        }
    }

    private void databaseProblem(Exception e) {
        String why = "This PC's database: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        System.err.println("[SYNC] " + why);
        status.problem(why);
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
        if (!terminalId.equals(e.source())) {
            foreignApplied = true;   // another register's change is now here: screens show their refresh icon
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
     * not moved back). For anything else, sync simply runs again.
     *
     * @return true if a failed change was retried and applied
     * @throws Exception it still can't be applied (the message says why)
     */
    public boolean retry(long syncLogId) throws Exception {
        synchronized (pullLock) {
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
    }

    /** Tells the screens (shared.DataChanged) if another register's changes were applied. */
    private void announceNewData() {
        if (foreignApplied) {
            foreignApplied = false;
            shared.DataChanged.fire();
        }
    }

    private void housekeeping() throws SQLException {
        Instant now = clock.instant();
        synchronized (this) {
            if (Duration.between(lastPurge, now).toHours() < 1) {
                return;
            }
            lastPurge = now;
        }
        db.purge(now.minus(KEEP_SENT), now.minus(KEEP_FEED));
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
        if (!changes.isEmpty()) {
            feedGrew();         // the other terminals waiting for news get it now
            wakeMain.run();     // and the Main POS applies it now
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

    /** As {@link #pull(long, int)}, but if nothing is new, waits up to {@code waitMillis} for something to arrive. */
    @Override
    public SyncHub.Page pull(long after, int limit, long waitMillis) throws SQLException, InterruptedException {
        long wait = Math.max(0, Math.min(waitMillis, MAX_WAIT_MILLIS));
        long seen;
        synchronized (feedSignal) {
            seen = feedVersion;
        }
        SyncHub.Page page = pull(after, limit);
        if (!page.changes().isEmpty() || wait == 0 || limit <= 0) {
            return page;
        }
        long end = System.currentTimeMillis() + wait;
        synchronized (feedSignal) {
            while (feedVersion == seen) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return page;
                }
                feedSignal.wait(left);
            }
        }
        return pull(after, limit);
    }

    private void feedGrew() {
        synchronized (feedSignal) {
            feedVersion++;
            feedSignal.notifyAll();
        }
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
