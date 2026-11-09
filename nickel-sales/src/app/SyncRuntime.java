package app;

import app.install.PosTerminalRepository;
import shared.MasterIds;
import shared.SyncOutbox;
import sync.MasterIdSources;
import sync.SyncDb;
import sync.SyncEngine;
import sync.SyncStatus;
import sync.SyncWorker;
import sync.api.ApiServer;
import sync.api.MainPosClient;
import sync.discovery.MainPosScanner;
import system.contract.SyncLogWriter;
import system.service.SyncLogService;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sync on this PC, once it is set up: the background worker that keeps its store data in step with
 * every other POS (sync.SyncEngine, sync.SyncWorker), where new shared IDs come from
 * (shared.MasterIds), and the Sync log's Retry.
 *
 * Made in {@link Main#wire} (so the status screens can show it from the start) and started by
 * {@link NickelSalesApp} when the app opens.
 */
public final class SyncRuntime {

    /** The longest a search for the Main POS on the network may take. */
    public static final java.time.Duration FIND_LIMIT = java.time.Duration.ofSeconds(5);

    private final SyncDb db;
    private final Clock clock;
    private volatile SyncWorker worker;

    public SyncRuntime(SyncDb db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SyncDb db() {
        return db;
    }

    /** This PC's sync status; empty until sync has started. */
    public Optional<SyncStatus> status() {
        SyncWorker w = worker;
        return w == null ? Optional.empty() : Optional.of(w.engine().status());
    }

    /** The engine, once started (the Main POS's API answers the sync calls with it). */
    public Optional<SyncEngine> engine() {
        SyncWorker w = worker;
        return w == null ? Optional.empty() : Optional.of(w.engine());
    }

    /** Runs a sync round now, if sync is running. */
    public void syncNow() {
        SyncWorker w = worker;
        if (w != null) {
            w.syncNow();
        }
    }

    /**
     * Starts sync for the terminal this PC was set up as. Does nothing if it isn't set up (or its
     * configuration can't be read).
     *
     * @return the engine, or empty if sync did not start
     */
    public synchronized Optional<SyncEngine> start(AppContext context, PosTerminalRepository terminals) {
        if (worker != null) {
            return engine();
        }
        Optional<String> me;
        try {
            me = terminals.thisTerminalId();
        } catch (RuntimeException e) {
            System.err.println("[SYNC] not started: this PC's terminal settings can't be read (" + e.getMessage() + ")");
            return Optional.empty();
        }
        if (me.isEmpty()) {
            return Optional.empty();
        }
        SyncLogWriter log = context.find(SyncLogWriter.class).orElse(null);
        SyncEngine engine;
        if (PosTerminalRepository.MAIN_ID.equals(me.get())) {
            engine = SyncEngine.mainPos(db, me.get(), terminals::updateAddress, log, clock);
            MasterIds.use(MasterIdSources.mainPos(db));
        } else {
            Supplier<MainPosClient> client = new MainLink(terminals);
            engine = SyncEngine.additional(db, me.get(), client,
                    // the router gave the Main POS a new address: find it on the network and follow it
                    () -> terminals.apiKey().flatMap(key -> MainPosScanner.find(ApiServer.port(), key, FIND_LIMIT,
                            terminals.mainPosAddress().orElse(null))),
                    ip -> terminals.updateAddress(PosTerminalRepository.MAIN_ID, ip), log, clock);
            MasterIds.use(MasterIdSources.additional(client, engine.status(), clock));
            engine.onMainName(name -> terminals.updateComputerName(PosTerminalRepository.MAIN_ID, name));
        }
        SyncWorker w = new SyncWorker(engine);
        context.find(SyncLogService.class).ifPresent(syncLog -> syncLog.setRetryHandler((logId, type, record) -> {
            if (!engine.retry(logId)) {
                w.syncNow();   // a send / receive problem: just try now
            }
        }));
        SyncOutbox.onRecorded(w::syncNow);   // a change saved here is sent at once
        worker = w;
        w.start();
        return Optional.of(engine);
    }

    public synchronized void stop() {
        SyncOutbox.onRecorded(null);
        if (worker != null) {
            worker.stop();
            worker = null;
        }
        MasterIds.use(null);
    }

    /**
     * A client for the Main POS at its saved address with the saved key; made again only when either
     * changes (an HTTP client is too heavy to make every second).
     */
    private static final class MainLink implements Supplier<MainPosClient> {
        private static final long REREAD_MILLIS = 3000;
        private final PosTerminalRepository terminals;
        private String ip;
        private String key;
        private MainPosClient client;

        MainLink(PosTerminalRepository terminals) {
            this.terminals = terminals;
        }

        private long readAt;

        @Override
        public synchronized MainPosClient get() {
            // The saved address is read again every few seconds (it changes only in Settings), not on
            // every call: sync calls this several times a second.
            long now = System.currentTimeMillis();
            if (client != null && now - readAt < REREAD_MILLIS) {
                return client;
            }
            readAt = now;
            String nowIp = terminals.mainPosAddress().orElse(null);
            String nowKey = terminals.apiKey().orElse(null);
            if (nowIp == null || nowKey == null) {
                return null;
            }
            if (client == null || !nowIp.equals(ip) || !nowKey.equals(key)) {
                ip = nowIp;
                key = nowKey;
                client = new MainPosClient(nowIp, ApiServer.port(), nowKey);
            }
            return client;
        }
    }
}
