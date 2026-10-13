package nickelsales.auth.contract;

import java.util.Collections;
import java.util.Set;

/**
 * CONTRACT ONLY — the User entity is owned by the DATA role.
 *
 * This file defines the exact shape the AuthenticationService reads.
 * The DATA implementation's real entity may replace or extend it freely, as
 * long as these getters exist with these meanings.
 *
 * NOTE: the Users table has no separate username column, so the User ID
 * ("NSC-00001") is what a person types at the login screen. UserName /
 * UserLastname are the person's actual name.
 */
public class User {

    private final String userId;
    private final String passwordHash; // bcrypt hash ($2a$..., 60 chars) — never plaintext
    private final String firstName;
    private final String lastName;
    private final Set<UserRole> roles;   // e.g. [ADMIN, CASHIER] — multi-role is normal
    private final UserStatus status;

    public User(String userId, String passwordHash, String firstName, String lastName,
                Set<UserRole> roles, UserStatus status) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.roles = roles == null ? Collections.emptySet() : Collections.unmodifiableSet(roles);
        this.status = status;
    }

    public String getUserId() {
        return userId;
    }

    /** What the user types at the login screen (the User ID). */
    public String getUsername() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getFullName() {
        return (firstName + " " + lastName).trim();
    }

    /** Convenience for the AUTHZ role's AuthorizationService (role-based checks). */
    public Set<UserRole> getRoles() {
        return roles;
    }

    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** toString deliberately never includes the password hash. */
    @Override
    public String toString() {
        return "User{" + userId + ", " + getFullName()
                + ", roles=" + roles + ", status=" + status + "}";
    }
}
