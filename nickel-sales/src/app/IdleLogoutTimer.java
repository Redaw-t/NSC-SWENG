package app;

import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TouchEvent;
import javafx.stage.Window;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Counts down while nobody uses the app and runs {@code onIdle} when the time
 * runs out. Any key press, mouse move/click, scroll or touch in ANY of the
 * app's windows (including dialogs) restarts the countdown.
 *
 * Only counts while {@link #start()} has been called (i.e. someone is logged in).
 */
final class IdleLogoutTimer {

    private final PauseTransition countdown;
    private final Runnable onActivity;
    private final Set<Window> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private final EventHandler<Event> activityHandler = e -> activity();
    private boolean running;

    /**
     * @param timeout    how long without activity before {@code onIdle} runs
     * @param onActivity called on every bit of activity while running (keep it cheap)
     * @param onIdle     called once when the countdown runs out
     */
    IdleLogoutTimer(Duration timeout, Runnable onActivity, Runnable onIdle) {
        Objects.requireNonNull(timeout, "timeout");
        this.onActivity = Objects.requireNonNull(onActivity, "onActivity");
        Objects.requireNonNull(onIdle, "onIdle");
        countdown = new PauseTransition(javafx.util.Duration.millis(timeout.toMillis()));
        countdown.setOnFinished(e -> {
            if (running) {
                running = false;
                onIdle.run();
            }
        });
        // Watch every window the app opens, now and later (dialogs are separate windows).
        Window.getWindows().forEach(this::watch);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(this::watch);
            }
        });
    }

    /** Starts (or restarts) the countdown. Call when a user logs in. */
    void start() {
        running = true;
        countdown.playFromStart();
    }

    /** Stops counting. Call when the user logs out. */
    void stop() {
        running = false;
        countdown.stop();
    }

    private void watch(Window window) {
        if (watched.add(window)) {
            window.addEventFilter(KeyEvent.KEY_PRESSED, activityHandler);
            window.addEventFilter(MouseEvent.MOUSE_MOVED, activityHandler);
            window.addEventFilter(MouseEvent.MOUSE_PRESSED, activityHandler);
            window.addEventFilter(MouseEvent.MOUSE_DRAGGED, activityHandler);
            window.addEventFilter(ScrollEvent.SCROLL, activityHandler);
            window.addEventFilter(TouchEvent.TOUCH_PRESSED, activityHandler);
        }
    }

    private void activity() {
        if (running) {
            countdown.playFromStart();
            onActivity.run();
        }
    }
}
