package sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs sync in the background, on daemon threads (they never keep the app from closing), so changes
 * move at once instead of every few seconds:
 *
 * - Main POS, "sync-main": moves its own changes into the list and applies the list, as soon as a
 *   change is saved here or arrives from another terminal ({@link #syncNow}), and at least every second.
 * - Additional POS:
 *   - "sync-send": sends this PC's changes as soon as one is saved ({@link #syncNow}), else every second;
 *   - "sync-receive": keeps a "wait for news" call open to the Main POS, so other terminals' changes
 *     arrive the moment they happen;
 *   - "sync-check": asks the Main POS every second whether it is on, so System Status and "this needs
 *     the Main POS" know straight away when it is off.
 */
public final class SyncWorker {

    /** The every-second check, and the longest a queued change waits if nothing wakes the worker. */
    static final long TICK_MILLIS = 1000;
    /** After a wake-up, a moment for the saving transaction to finish before its change is read. */
    static final long SETTLE_MILLIS = 100;

    private final SyncEngine engine;
    private final Object signal = new Object();
    private boolean woken;
    private volatile boolean running;
    private final List<Thread> threads = new ArrayList<>();

    public SyncWorker(SyncEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        engine.started();
        if (engine.isMainPos()) {
            engine.onFeedGrew(this::syncNow);
            thread("sync-main", () -> {
                while (running) {
                    engine.mainRound();
                    awaitWake();
                }
            });
        } else {
            thread("sync-send", () -> {
                while (running) {
                    engine.pushRound();
                    awaitWake();
                }
            });
            thread("sync-receive", () -> {
                while (running) {
                    engine.pullRound(SyncEngine.MAX_WAIT_MILLIS);
                    if (!Boolean.TRUE.equals(engine.status().mainReachable())) {
                        pause(TICK_MILLIS);   // Main POS off: try again in a second, not in a tight loop
                    }
                }
            });
            thread("sync-check", () -> {
                while (running) {
                    engine.heartbeat();
                    pause(TICK_MILLIS);
                }
            });
        }
        System.out.println("[SYNC] started on this " + (engine.isMainPos() ? "Main POS" : "Additional POS"));
    }

    /** Runs a round now: a change was just saved here (shared.SyncOutbox) or a Retry asked for it. */
    public void syncNow() {
        synchronized (signal) {
            woken = true;
            signal.notifyAll();
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        engine.onFeedGrew(null);
        for (Thread t : threads) {
            t.interrupt();
        }
        threads.clear();
        engine.stopped();
    }

    public SyncEngine engine() {
        return engine;
    }

    /* ------------------------------------ helpers ------------------------------------ */

    private void thread(String name, Runnable loop) {
        Thread t = new Thread(() -> {
            try {
                loop.run();
            } catch (RuntimeException e) {
                System.err.println("[SYNC] " + name + " stopped: " + e);
            }
        }, name);
        t.setDaemon(true);
        threads.add(t);
        t.start();
    }

    /** Waits until woken or a second has passed; after a wake-up, lets the saving transaction finish. */
    private void awaitWake() {
        boolean wasWoken;
        synchronized (signal) {
            if (!woken) {
                try {
                    signal.wait(TICK_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            wasWoken = woken;
            woken = false;
        }
        if (wasWoken) {
            pause(SETTLE_MILLIS);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
