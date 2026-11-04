package system.model;

import java.util.Objects;

/**
 * One line of the system status: a component's health at this moment. A data holder with no logic.
 *
 * @param name   e.g. "Database"
 * @param level  how it is doing
 * @param value  the short answer, e.g. "Connected"
 * @param detail one sentence with more; may be null
 */
public record StatusItem(String name, Level level, String value, String detail) {

    public enum Level {
        OK("OK"), INFO("Info"), WARNING("Warning"), ERROR("Problem");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public StatusItem {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(value, "value");
    }
}
