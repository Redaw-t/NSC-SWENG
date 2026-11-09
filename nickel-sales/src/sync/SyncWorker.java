package sync;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link SyncEngine#cycle} in the background every few seconds, on one daemon thread (it never
 * keeps the app from closing). {@link #syncNow} runs a round straight away, e.g. after a Retry.
 */
public final class SyncWorker {

    /** How often a round runs. */
    public static final Duration EVERY = Duration.ofSeconds(3);

    private final SyncEngine engine;
    private final Duration every;
    private ScheduledExecutorService thread;

    public SyncWorker(SyncEngine engine) {
        this(engine, EVERY);
    }

    public SyncWorker(SyncEngine engine, Duration every) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.every = Objects.requireNonNull(every, "every");
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync");
            t.setDaemon(true);
            return t;
        });
        engine.started();
        thread.scheduleWithFixedDelay(engine::cycle, 1000, every.toMillis(), TimeUnit.MILLISECONDS);
        System.out.println("[SYNC] started on this " + (engine.isMainPos() ? "Main POS" : "Additional POS")
                + ", every " + every.toSeconds() + " s");
    }

    /** Runs a round as soon as possible (in the background). */
    public synchronized void syncNow() {
        if (thread != null) {
            thread.execute(engine::cycle);
        }
    }

    public synchronized void stop() {
        if (thread != null) {
            thread.shutdownNow();
            thread = null;
            engine.stopped();
        }
    }

    public SyncEngine engine() {
        return engine;
    }
}
