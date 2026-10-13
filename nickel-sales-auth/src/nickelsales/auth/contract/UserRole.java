package nickelsales.auth.contract;

import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * CONTRACT ONLY — shared vocabulary.
 *
 * Roles from the class diagram, mapped to the single-letter codes stored in
 * the Users.UserRole column. The database stores one or more codes separated
 * by semicolons (e.g. "A;C;I") because one person can hold several roles.
 */
public enum UserRole {
    ADMIN("A"),
    CASHIER("C"),
    INVENTORY_STAFF("I");

    private final String dbCode;

    UserRole(String dbCode) {
        this.dbCode = dbCode;
    }

    public String getDbCode() {
        return dbCode;
    }

    /** Parses "A;C;I" into [ADMIN, CASHIER, INVENTORY_STAFF]. Unknown codes are skipped. */
    public static Set<UserRole> parseDbRoles(String dbRoles) {
        Set<UserRole> roles = new java.util.LinkedHashSet<>();
        if (dbRoles != null) {
            for (String part : dbRoles.split(";")) {
                String code = part.trim().toUpperCase(Locale.ROOT);
                for (UserRole role : values()) {
                    if (role.dbCode.equals(code)) {
                        roles.add(role);
                    }
                }
            }
        }
        return roles;
    }

    /** Inverse of {@link #parseDbRoles}: [ADMIN, CASHIER] back to "A;C". */
    public static String toDbCodes(Set<UserRole> roles) {
        StringJoiner joiner = new StringJoiner(";");
        for (UserRole role : roles) {
            joiner.add(role.dbCode);
        }
        return joiner.toString();
    }

    /** Lenient parse of a role name such as "admin" or "INVENTORY_STAFF". */
    public static UserRole fromName(String name) {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
}
