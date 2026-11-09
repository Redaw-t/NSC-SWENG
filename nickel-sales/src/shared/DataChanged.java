package shared;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "New data arrived from another register" (sync.SyncEngine fires it after applying changes made
 * elsewhere). Screens listen to show their refresh icon. Listeners are held weakly: a screen that is
 * gone is forgotten without having to sign off. Plain Java: a listener that touches the screen must
 * switch to the FX thread itself.
 */
public final class DataChanged {

    private static final List<WeakReference<Runnable>> LISTENERS = new CopyOnWriteArrayList<>();

    private DataChanged() { }

    /**
     * Calls {@code listener} whenever new data arrives. Held weakly: keep a reference to it for as long
     * as it should be called (e.g. in the screen's node properties).
     */
    public static void listen(Runnable listener) {
        LISTENERS.add(new WeakReference<>(listener));
    }

    /** New data arrived: tells every listener still around. */
    public static void fire() {
        for (WeakReference<Runnable> ref : LISTENERS) {
            Runnable r = ref.get();
            if (r == null) {
                LISTENERS.remove(ref);
                continue;
            }
            try {
                r.run();
            } catch (RuntimeException e) {
                System.err.println("[DATA] a listener failed: " + e);
            }
        }
    }
}
