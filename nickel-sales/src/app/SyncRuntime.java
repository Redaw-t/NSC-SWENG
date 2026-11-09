package app;

import app.install.PosTerminalRepository;
import shared.MasterIds;
import sync.MasterIdSources;
import sync.SyncDb;
import sync.SyncEngine;
import sync.SyncStatus;
import sync.SyncWorker;
import sync.api.ApiServer;
import sync.api.MainPosClient;
import sync.discovery.Discovery;
import system.contract.SyncLogWriter;
import system.service.SyncLogService;

import java.net.SocketException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sync on this PC, once it is set up: the background worker that keeps its store data in step with
 * every other POS (sync.SyncEngine), where new shared IDs come from (shared.MasterIds), the Sync log's
 * Retry, and on the Main POS the network answer that lets other PCs find it (sync.discovery).
 *
 * Made in {@link Main#wire} (so the status screens can show it from the start) and started by
 * {@link NickelSalesApp} when the app opens.
 */
public final class SyncRuntime {

    /** How long a PC listens for the Main POS's answer when it looks for it on the network. */
    static final Duration FIND_WAIT = Duration.ofSeconds(2);

    private final SyncDb db;
    private final Clock clock;
    private volatile SyncWorker worker;
    private Discovery.Responder responder;

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
            responder = new Discovery.Responder(me.get(), ApiServer.port());
            try {
                responder.start();
            } catch (SocketException e) {
                System.err.println("[DISCOVERY] could not answer on UDP " + Discovery.PORT + " (" + e.getMessage()
                        + "); other terminals can still use the IP address.");
                responder = null;
            }
        } else {
            Supplier<MainPosClient> client = new MainLink(terminals);
            engine = SyncEngine.additional(db, me.get(), client, ip -> terminals.updateAddress(PosTerminalRepository.MAIN_ID, ip),
                    () -> terminals.apiKey().flatMap(key -> Discovery.find(ApiServer.port(), key, FIND_WAIT)),
                    log, clock);
            MasterIds.use(MasterIdSources.additional(client, engine.status(), clock));
        }
        SyncWorker w = new SyncWorker(engine);
        context.find(SyncLogService.class).ifPresent(syncLog -> syncLog.setRetryHandler((logId, type, record) -> {
            if (!engine.retry(logId)) {
                w.syncNow();   // a send / receive problem: just try now
            }
        }));
        worker = w;
        w.start();
        return Optional.of(engine);
    }

    public synchronized void stop() {
        if (worker != null) {
            worker.stop();
            worker = null;
        }
        if (responder != null) {
            responder.stop();
            responder = null;
        }
        MasterIds.use(null);
    }

    /**
     * A client for the Main POS at its saved address with the saved key; made again only when either
     * changes (an HTTP client is too heavy to make every few seconds).
     */
    private static final class MainLink implements Supplier<MainPosClient> {
        private final PosTerminalRepository terminals;
        private String ip;
        private String key;
        private MainPosClient client;

        MainLink(PosTerminalRepository terminals) {
            this.terminals = terminals;
        }

        @Override
        public synchronized MainPosClient get() {
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
