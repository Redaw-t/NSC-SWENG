package system.ui;

import app.ScreenContext;

import java.util.HashMap;
import java.util.Map;

/**
 * The people named in a tab's rows. Each name is looked up once, when the rows load, so drawing the
 * table never touches the database. A lookup that fails is not remembered: the next load tries again.
 */
final class UserNames {

    private final ScreenContext screen;
    private final Map<String, String> names = new HashMap<>();   // user ID -> name, or the ID if unknown

    UserNames(ScreenContext screen) {
        this.screen = screen;
    }

    void remember(String userId) {
        if (userId == null || names.containsKey(userId)) {
            return;
        }
        try {
            String name = screen.app().userName(userId);
            names.put(userId, name == null ? userId : name);
        } catch (RuntimeException ignored) {
            // the ID is shown until a later load finds the name
        }
    }

    /** "Juan Dela Cruz" for a table; the ID itself when the name is not known. */
    String name(String userId) {
        return userId == null ? "" : names.getOrDefault(userId, userId);
    }

    /** "Juan Dela Cruz (NSC-00002)", or just the ID when the name is not known. */
    String label(String userId) {
        if (userId == null) {
            return "";
        }
        String name = name(userId);
        return name.equals(userId) ? userId : name + " (" + userId + ")";
    }

    /** The name, or null when it is not known (the exports leave the column blank). */
    String nameOrNull(String userId) {
        String name = names.get(userId);
        return name == null || name.equals(userId) ? null : name;
    }
}
