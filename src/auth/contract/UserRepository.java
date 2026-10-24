package auth.contract;

import java.util.List;

/**
 * CONTRACT ONLY — implemented by the DATA role.
 *
 * AuthenticationService talks ONLY to this interface. The DATA
 * implementation (MySQL, the Users table from NickelSales-Database-Config.sql,
 * or anything else) drops in without touching authentication.
 */
public interface UserRepository {

    /** @return the User with this ID, or null when not found. */
    User findById(String userId);

    /**
     * Login lookup. The Users table has no separate username column, so the
     * User ID IS the login id. Implementations should be tolerant of case
     * and surrounding whitespace (" nsc-00002 " still finds NSC-00002).
     *
     * @return the User, or null when not found.
     */
    User findByUsername(String username);

    /** Inserts a new user. Fails (RuntimeException) if the ID already exists. */
    void save(User user);

    /** Updates an existing user. Fails (RuntimeException) if the ID does not exist. */
    void update(User user);

    /** Replaces only the stored bcrypt hash. Fails (RuntimeException) if the ID does not exist. */
    void updatePassword(String userId, String newPasswordHash);

    /** All users (used by user management screens, not by login). */
    List<User> findAll();
}
