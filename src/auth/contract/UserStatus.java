package auth.contract;

import java.util.Locale;

/**
 * CONTRACT ONLY — shared vocabulary.
 *
 * Account status from the class diagram. The Users.UserStatus column stores
 * "Active"/"Inactive" (mixed case); this enum normalizes that exactly once.
 */
public enum UserStatus {
    ACTIVE,
    INACTIVE,
    LOCKED;

    /** Parses "Active", "inactive", "LOCKED", etc. Unknown/null values become INACTIVE (fail closed). */
    public static UserStatus fromDbString(String dbValue) {
        if (dbValue != null) {
            String normalized = dbValue.trim().toUpperCase(Locale.ROOT);
            for (UserStatus status : values()) {
                if (status.name().equals(normalized)) {
                    return status;
                }
            }
        }
        return INACTIVE;
    }

    public String toDbString() {
        // Matches the style used in NickelSales-Database-Config.sql
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }
}
