package app;

import auth.permission.Permission;
import javafx.scene.Parent;

/**
 * One button on the home screen and the screen it opens. Every module (POS,
 * users, inventory, reports, sync) has one, listed in {@link Modules}.
 *
 * The home screen shows the button only to users who have
 * {@link #permission()}; the router checks it again when the button is
 * pressed, so a screen never opens for someone who lost access mid-session.
 */
public interface AppModule {

    /** Button text, e.g. "Point of Sale". Short; unique across modules. */
    String title();

    /** One line shown under the title, e.g. "Ring up sales". */
    String description();

    /** Who may see and open it (see auth.permission.AuthorizationService). */
    Permission permission();

    /**
     * Builds the module's screen. Called each time the button is pressed.
     * Use {@code screen.goHome()} for your Back button.
     */
    Parent open(ScreenContext screen);
}
